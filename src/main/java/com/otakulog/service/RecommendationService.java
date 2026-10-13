package com.otakulog.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.otakulog.common.*;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.dto.RecommendationDTO.*;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RecommendationService {
    private final BangumiService bangumi;
    private final RecommendationState state;
    private final ExecutorService upstream=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(120),task -> {var t=new Thread(task,"recommendation-upstream");t.setDaemon(true);return t;});
    private final Semaphore slots=new Semaphore(2);
    private final Cache<String,Batch> batches=Caffeine.newBuilder().maximumSize(20).expireAfterWrite(Duration.ofMinutes(15)).build();
    private Clock clock=Clock.systemDefaultZone();
    private record Batch(String owner,String fingerprint,Instant expiresAt,LocalDateTime shownAt,List<Candidate> candidates) {}
    public RecommendationService(BangumiService bangumi,RecommendationState state){this.bangumi=bangumi;this.state=state;}
    @PreDestroy public void close(){upstream.shutdownNow();}
    public Result recommend(String owner,boolean includeRecent){
        RecommendationState.owner(owner);var snapshot=state.snapshot();var settings=snapshot.settings();
        if(snapshot.seedTags().isEmpty())return new Result(settings,List.of(),List.of(),null,"COLD_START",Map.of(),0,0);
        if(!slots.tryAcquire())throw new ExternalApiException("推荐正在核对，请稍后重试");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);var submitted=new ArrayList<Future<?>>();
        try{
            var sourceFutures=new ArrayList<Future<List<BangumiResult>>>();
            for(String tag:snapshot.seedTags()){var f=upstream.submit(() -> bangumi.searchTaggedSubjects(tag,20,settings.minYear(),settings.maxYear()));sourceFutures.add(f);submitted.add(f);}
            var ids=new TreeSet<Integer>();int sourceErrors=0;
            for(var f:sourceFutures){try{var rows=f.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(rows==null)throw new IllegalStateException("候选响应缺失");
                for(var row:rows){if(row==null||row.getId()<1)throw new IllegalStateException("候选编号无效");ids.add(row.getId());}}catch(Exception e){sourceErrors++;}}
            if(sourceErrors==sourceFutures.size())throw new ExternalApiException("推荐来源读取失败，请稍后重试");
            if(ids.size()>60)throw new ExternalApiException("推荐候选超过核对上限");
            var details=new LinkedHashMap<Integer,Future<BangumiSubjectDetail>>();
            for(int id:ids){var f=upstream.submit(() -> bangumi.getSubject(id));details.put(id,f);submitted.add(f);}
            var results=new ArrayList<Candidate>();var excluded=new TreeMap<String,Integer>();int unverified=0;
            for(var item:details.entrySet()){
                BangumiSubjectDetail d;
                try{d=item.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(Exception e){unverified++;continue;}
                int id=item.getKey();
                if(d==null||d.getId()!=id||!Integer.valueOf(2).equals(d.getType())||d.getAllTags()==null
                        ||d.getName()==null||d.getName().isBlank()||d.getName().length()>1000||d.getAllTags().stream().anyMatch(t -> t==null||t.isBlank()||t.length()>200)){unverified++;continue;}
                String nameCn=d.getNameCn()==null?"":d.getNameCn();if(nameCn.length()>1000){unverified++;continue;}
                var tags=List.copyOf(d.getAllTags());
                var matches=snapshot.seedTags().stream().filter(seed -> tags.stream().anyMatch(t -> RecommendationPolicy.normalize(seed).equals(RecommendationPolicy.normalize(t)))).toList();
                if(matches.isEmpty()){excluded.merge("NO_MATCH",1,Integer::sum);continue;}
                Integer year=null;String date="";
                try{if(d.getAirDate()!=null&&!d.getAirDate().isBlank()){var parsed=LocalDate.parse(d.getAirDate());year=parsed.getYear();date=parsed.toString();}}catch(Exception ignored){}
                boolean tracked=snapshot.tracked().contains(id)||snapshot.names().contains(RecommendationPolicy.normalize(d.getName()))||(!nameCn.isBlank()&&snapshot.names().contains(RecommendationPolicy.normalize(nameCn)));
                boolean watched=snapshot.watched().contains(id),recent=snapshot.recent().contains(id);
                String reason=snapshot.blockedExternal().contains(id)?"EXTERNAL":RecommendationPolicy.evaluate(year,tags,settings.minYear(),settings.maxYear(),settings.excludedTags(),tracked,watched,snapshot.rejected().contains(id),settings.includeTracked(),recent,includeRecent);
                if(reason!=null){excluded.merge(reason,1,Integer::sum);continue;}
                Double score=d.getRating();if(score!=null&&(!Double.isFinite(score)||score<0||score>10))score=null;
                String image=d.getCoverUrl()==null?"":d.getCoverUrl();if(image.length()>2048)image="";
                results.add(new Candidate(id,d.getName(),nameCn,image,score,date,year,tags,"实际标签："+String.join("、",matches),tracked,watched,recent));
            }
            if(!ids.isEmpty()&&unverified==ids.size())throw new ExternalApiException("候选详情暂时无法核对，请稍后重试");
            results.sort(Comparator.comparingInt((Candidate c) -> (int)snapshot.seedTags().stream().filter(seed -> c.tags().stream().anyMatch(t -> RecommendationPolicy.normalize(seed).equals(RecommendationPolicy.normalize(t)))).count()).reversed()
                    .thenComparing(Candidate::score,Comparator.nullsLast(Comparator.reverseOrder())).thenComparingInt(Candidate::id));
            if(!snapshot.fingerprint().equals(state.snapshot().fingerprint()))throw new ConflictException("推荐条件或资料已变化，请重新读取");
            List<Candidate> entries=List.copyOf(results.stream().limit(10).toList());String key=null;
            if(!entries.isEmpty()){key=UUID.randomUUID().toString();batches.put(key,new Batch(owner,snapshot.guardFingerprint(),clock.instant().plusSeconds(900),state.now(),entries));}
            return new Result(settings,snapshot.seedTags(),entries,key,entries.isEmpty()?"EMPTY":"READY",excluded,sourceErrors,unverified);
        }finally{for(var f:submitted)if(!f.isDone())f.cancel(true);slots.release();}
    }
    public FeedbackEntry feedback(String owner,FeedbackRequest request){
        RecommendationState.owner(owner);
        if(request==null||request.subjectId()==null||request.subjectId()<1||request.kind()==null)throw new IllegalArgumentException("反馈编号或类型无效");
        BangumiImportPreviewService.uuid(request.requestKey());BangumiImportPreviewService.uuid(request.batchKey());
        return state.locked(() -> {
            var previous=state.previous(owner,request);if(previous!=null)return previous;
            var batch=batch(owner,request.batchKey());checkCurrent(batch);
            var candidate=batch.candidates().stream().filter(c -> c.id()==request.subjectId()).findFirst().orElseThrow(() -> new IllegalArgumentException("作品不在本次推荐中"));
            return state.createFeedback(owner,request,candidate);
        });
    }
    public void shown(String owner,ShownRequest request){
        RecommendationState.owner(owner);
        if(request==null||request.subjectIds()==null||request.subjectIds().isEmpty()||request.subjectIds().size()>10
                ||request.subjectIds().stream().anyMatch(id -> id==null||id<1)||new HashSet<>(request.subjectIds()).size()!=request.subjectIds().size())throw new IllegalArgumentException("展示记录编号无效");
        BangumiImportPreviewService.uuid(request.batchKey());
        state.locked(() -> {var batch=batch(owner,request.batchKey());checkCurrent(batch);
            var allowed=batch.candidates().stream().map(Candidate::id).toList();if(!allowed.containsAll(request.subjectIds()))throw new IllegalArgumentException("作品不在本次推荐中");
            state.shown(request.batchKey(),batch.shownAt(),request.subjectIds());return null;});
    }
    private Batch batch(String owner,String key){var batch=batches.getIfPresent(key);if(batch==null||!batch.owner().equals(owner)||!clock.instant().isBefore(batch.expiresAt()))throw new ResourceNotFoundException("推荐批次不存在或过期，请刷新");return batch;}
    private void checkCurrent(Batch batch){if(!batch.fingerprint().equals(state.snapshotValue().guardFingerprint()))throw new ConflictException("推荐条件或资料已变化，请刷新后操作");}
}
