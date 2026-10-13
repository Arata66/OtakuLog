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
    private final RecommendationBasisService basisService;
    private final ExecutorService upstream=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(120),task -> {var t=new Thread(task,"recommendation-upstream");t.setDaemon(true);return t;});
    private final Semaphore slots=new Semaphore(2);
    private final Cache<String,Batch> batches=Caffeine.newBuilder().maximumSize(20).expireAfterWrite(Duration.ofMinutes(15)).build();
    private Clock clock=Clock.systemDefaultZone();
    private record Batch(String owner,String fingerprint,Instant expiresAt,LocalDateTime shownAt,List<Candidate> candidates) {}
    public RecommendationService(BangumiService bangumi,RecommendationState state,RecommendationBasisService basisService){this.bangumi=bangumi;this.state=state;this.basisService=basisService;}
    @PreDestroy public void close(){upstream.shutdownNow();}
    public Result recommend(String owner,boolean includeRecent){
        return recommend(owner,includeRecent,Mode.AUTO,null,List.of(),List.of());
    }
    public Result recommend(String owner,boolean includeRecent,Mode mode,Integer subjectId,List<String> inputTags,List<String> basisKeys){
        RecommendationState.owner(owner);var snapshot=state.snapshot();var settings=snapshot.settings();
        validateMode(mode,subjectId,inputTags,basisKeys);
        var selected=new ArrayList<com.otakulog.dto.RecommendationBasisDTO.Entry>();
        if(mode==Mode.TASTE)for(String key:basisKeys){var entry=snapshot.bases().entries().stream().filter(e -> e.data().key().equals(key)).findFirst().orElseThrow(() -> new ConflictException("选择的关联已缺失，请重新核对"));
            if(!entry.state().equals("ACTIVE"))throw new ConflictException("选择的关联已停用或来源变化，请重新核对");selected.add(entry);}
        long likes=selected.stream().filter(e -> e.data().direction()==com.otakulog.enums.InsightDirection.LIKE).count();
        if(likes>3||selected.size()-likes>50)throw new IllegalArgumentException("本轮最多3条喜欢和50条避开关联");
        if(mode==Mode.AUTO&&snapshot.seedTags().isEmpty())return new Result(settings,List.of(),List.of(),null,"COLD_START",Map.of(),0,0);
        if((mode==Mode.TAGS&&inputTags.isEmpty())||(mode==Mode.TASTE&&likes==0))return new Result(settings,List.of(),List.of(),null,mode==Mode.TAGS?"COLD_START_TAGS":"COLD_START_TASTE",Map.of(),0,0,new Context(mode,"",List.of(),List.of()));
        if(!slots.tryAcquire())throw new ExternalApiException("推荐正在核对，请稍后重试");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);var submitted=new ArrayList<Future<?>>();
        try{
            var recall=new LinkedHashMap<String,String>();var evidence=new ArrayList<UsedBasis>();var avoid=new ArrayList<String>();var seedIds=new HashSet<Integer>();String seedName="";
            if(mode==Mode.AUTO)for(String tag:snapshot.seedTags())recall.putIfAbsent(RecommendationPolicy.normalize(tag),tag);
            if(mode==Mode.TAGS)for(String tag:inputTags)recall.putIfAbsent(RecommendationPolicy.normalize(tag),tag);
            if(mode==Mode.SEED){var d=seed(subjectId,deadline,submitted);seedName=RecommendationBasisService.display(d);seedIds.add(subjectId);
                var real=distinctTags(d.getAllTags());var chosen=inputTags.isEmpty()?real.stream().limit(3).toList():inputTags;
                if(chosen.stream().anyMatch(t -> real.stream().noneMatch(actual -> RecommendationPolicy.normalize(actual).equals(RecommendationPolicy.normalize(t)))))throw new IllegalArgumentException("选择的标签不属于种子实际标签");
                for(String tag:chosen)recall.putIfAbsent(RecommendationPolicy.normalize(tag),tag);}
            var proposedEvidence=new ArrayList<UsedBasis>();
            for(var entry:selected){var b=entry.data();List<String> tags;
                if(b.direction()==com.otakulog.enums.InsightDirection.DISLIKE){tags=List.of(b.tag());avoid.add(b.tag());}
                else {if(b.kind()==com.otakulog.dto.RecommendationBasisDTO.Kind.TAG)tags=List.of(b.tag());else{var d=seed(b.subjectId(),deadline,submitted);seedIds.add(b.subjectId());tags=distinctTags(d.getAllTags()).stream().limit(3).toList();}
                    String first=tags.get(0);recall.putIfAbsent(RecommendationPolicy.normalize(first),first);}
                proposedEvidence.add(new UsedBasis(b.key(),entry.source(),tags));}
            // 先让每条喜欢都有实际依据，避免第一部种子的标签挤掉其他明确选择。
            for(var e:proposedEvidence)if(e.source().direction()==com.otakulog.enums.InsightDirection.LIKE)for(String tag:e.tags())if(recall.size()<3)recall.putIfAbsent(RecommendationPolicy.normalize(tag),tag);
            for(var e:proposedEvidence)evidence.add(new UsedBasis(e.key(),e.source(),e.source().direction()==com.otakulog.enums.InsightDirection.DISLIKE?e.tags():e.tags().stream().filter(t -> recall.containsKey(RecommendationPolicy.normalize(t))).toList()));
            var seeds=List.copyOf(recall.values());var context=new Context(mode,seedName,List.copyOf(evidence),List.copyOf(avoid));
            if(avoid.stream().anyMatch(t -> recall.containsKey(RecommendationPolicy.normalize(t))))return new Result(settings,seeds,List.of(),null,"BASIS_CONFLICT",Map.of(),0,0,context);
            var sourceFutures=new ArrayList<Future<List<BangumiResult>>>();
            for(String tag:seeds){var f=upstream.submit(() -> bangumi.searchTaggedSubjects(tag,20,settings.minYear(),settings.maxYear()));sourceFutures.add(f);submitted.add(f);}
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
                if(seedIds.contains(id)){excluded.merge("SEED",1,Integer::sum);continue;}
                if(d==null||d.getId()!=id||!Integer.valueOf(2).equals(d.getType())||d.getAllTags()==null
                        ||d.getName()==null||d.getName().isBlank()||d.getName().length()>1000||d.getAllTags().stream().anyMatch(t -> t==null||t.isBlank()||t.length()>200)){unverified++;continue;}
                String nameCn=d.getNameCn()==null?"":d.getNameCn();if(nameCn.length()>1000){unverified++;continue;}
                var tags=List.copyOf(d.getAllTags());
                var matches=seeds.stream().filter(seed -> tags.stream().anyMatch(t -> RecommendationPolicy.normalize(seed).equals(RecommendationPolicy.normalize(t)))).toList();
                if(matches.isEmpty()){excluded.merge("NO_MATCH",1,Integer::sum);continue;}
                Integer year=null;String date="";
                try{if(d.getAirDate()!=null&&!d.getAirDate().isBlank()){var parsed=LocalDate.parse(d.getAirDate());year=parsed.getYear();date=parsed.toString();}}catch(Exception ignored){}
                boolean tracked=snapshot.tracked().contains(id)||snapshot.names().contains(RecommendationPolicy.normalize(d.getName()))||(!nameCn.isBlank()&&snapshot.names().contains(RecommendationPolicy.normalize(nameCn)));
                boolean watched=snapshot.watched().contains(id),recent=snapshot.recent().contains(id);
                String reason=snapshot.blockedExternal().contains(id)?"EXTERNAL":RecommendationPolicy.evaluate(year,tags,settings.minYear(),settings.maxYear(),settings.excludedTags(),tracked,watched,snapshot.rejected().contains(id),settings.includeTracked(),recent,includeRecent);
                if(reason!=null){excluded.merge(reason,1,Integer::sum);continue;}
                if(avoid.stream().anyMatch(t -> tags.stream().anyMatch(actual -> RecommendationPolicy.normalize(t).equals(RecommendationPolicy.normalize(actual))))){excluded.merge("TASTE_TAG",1,Integer::sum);continue;}
                Double score=d.getRating();if(score!=null&&(!Double.isFinite(score)||score<0||score>10))score=null;
                String image=d.getCoverUrl()==null?"":d.getCoverUrl();if(image.length()>2048)image="";
                String rationale="实际标签："+String.join("、",matches);
                if(mode==Mode.SEED)rationale="与「"+seedName+"」共同标签："+String.join("、",matches);
                if(mode==Mode.TASTE){var factors=evidence.stream().filter(e -> e.source().direction()==com.otakulog.enums.InsightDirection.LIKE&&e.tags().stream().anyMatch(t -> matches.stream().anyMatch(m -> RecommendationPolicy.normalize(t).equals(RecommendationPolicy.normalize(m))))).map(e -> e.source().factor()).distinct().toList();
                    rationale+="；已确认关联："+String.join("、",factors)+"。仅证明标签匹配。";}
                results.add(new Candidate(id,d.getName(),nameCn,image,score,date,year,tags,rationale,tracked,watched,recent));
            }
            if(!ids.isEmpty()&&unverified==ids.size())throw new ExternalApiException("候选详情暂时无法核对，请稍后重试");
            results.sort(Comparator.comparingInt((Candidate c) -> (int)seeds.stream().filter(seed -> c.tags().stream().anyMatch(t -> RecommendationPolicy.normalize(seed).equals(RecommendationPolicy.normalize(t)))).count()).reversed()
                    .thenComparing(Candidate::score,Comparator.nullsLast(Comparator.reverseOrder())).thenComparingInt(Candidate::id));
            if(!snapshot.fingerprint().equals(state.snapshot().fingerprint()))throw new ConflictException("推荐条件或资料已变化，请重新读取");
            List<Candidate> entries=List.copyOf(results.stream().limit(10).toList());String key=null;
            if(!entries.isEmpty()){key=UUID.randomUUID().toString();batches.put(key,new Batch(owner,snapshot.guardFingerprint(),clock.instant().plusSeconds(900),state.now(),entries));}
            return new Result(settings,seeds,entries,key,entries.isEmpty()?"EMPTY":"READY",excluded,sourceErrors,unverified,context);
        }finally{for(var f:submitted)if(!f.isDone())f.cancel(true);slots.release();}
    }
    private void validateMode(Mode mode,Integer subjectId,List<String> tags,List<String> basisKeys){
        if(mode==null||tags==null||basisKeys==null)throw new IllegalArgumentException("推荐模式无效");RecommendationState.cleanTags(tags,3);
        if(mode==Mode.SEED?(subjectId==null||subjectId<1):subjectId!=null)throw new IllegalArgumentException("只有按作品模式需要有效作品编号");
        if(mode!=Mode.TAGS&&mode!=Mode.SEED&&!tags.isEmpty())throw new IllegalArgumentException("当前模式不接受临时标签");
        if(mode!=Mode.TASTE&&!basisKeys.isEmpty())throw new IllegalArgumentException("只有口味模式接受关联选择");
        if(basisKeys.size()>53||new HashSet<>(basisKeys).size()!=basisKeys.size())throw new IllegalArgumentException("关联选择重复或超过上限");for(String key:basisKeys)BangumiImportPreviewService.uuid(key);
    }
    private BangumiSubjectDetail seed(int id,long deadline,List<Future<?>> submitted){
        var f=upstream.submit(() -> basisService.seed(id));submitted.add(f);
        try{return f.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException runtime)throw runtime;throw new ExternalApiException("种子详情核对失败");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExternalApiException("种子核对已中断");}
        catch(Exception e){throw new ExternalApiException("种子核对超时，请重试");}
    }
    private List<String> distinctTags(List<String> tags){var values=new LinkedHashMap<String,String>();for(String tag:tags)values.putIfAbsent(RecommendationPolicy.normalize(tag),tag);return List.copyOf(values.values());}
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
