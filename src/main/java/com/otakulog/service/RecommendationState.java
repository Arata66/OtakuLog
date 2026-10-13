package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.common.ConflictException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.RecommendationDTO.*;
import com.otakulog.entity.*;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

@Service
public class RecommendationState {
    private final RecommendationPreferenceRepository preferences;
    private final RecommendationFeedbackRepository feedback;
    private final RecommendationShownRepository shown;
    private final AnimeRepository anime;
    private final ExternalCollectionRepository external;
    private final ImportLockRepository locks;
    private final ObjectMapper mapper;
    private final RecommendationBasisService bases;
    private final TransactionTemplate read, write;
    private Clock clock=Clock.systemDefaultZone();
    public RecommendationState(RecommendationPreferenceRepository preferences,RecommendationFeedbackRepository feedback,
            RecommendationShownRepository shown,AnimeRepository anime,ExternalCollectionRepository external,
            ImportLockRepository locks,ObjectMapper mapper,PlatformTransactionManager manager,RecommendationBasisService bases) {
        this.preferences=preferences;this.feedback=feedback;this.shown=shown;this.anime=anime;this.external=external;this.locks=locks;this.mapper=mapper;
        this.bases=bases;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);
    }
    public record Snapshot(Settings settings,List<String> seedTags,Set<Integer> tracked,Set<String> names,Set<Integer> watched,
            Set<Integer> blockedExternal,Set<Integer> rejected,Set<Integer> recent,String fingerprint,String guardFingerprint,com.otakulog.dto.RecommendationBasisDTO.Page bases) {}
    public Settings settings() { return read.execute(s -> settingsValue()); }
    public Settings settingsValue() { return preferences.findById(1L).map(this::settings).orElse(new Settings(null,0L,null,null,List.of(),List.of(),false)); }
    public Settings settings(RecommendationPreference p) {
        return new Settings(p.getPreferenceKey(),p.getVersion(),p.getMinYear(),p.getMaxYear(),tags(p.getExcludedTagsJson()),tags(p.getRecallTagsJson()),p.isIncludeTracked());
    }
    public Settings save(Settings input) {
        validate(input,false);
        return locked(() -> {
            var current=settingsValue();
            if(!Objects.equals(current.key(),input.key())||!Objects.equals(current.version(),input.version())) throw new ConflictException("推荐条件已变化，请重新读取后保存");
            var p=preferences.findById(1L).orElseGet(RecommendationPreference::new);
            if(p.getId()==null) {p.setId(1L);p.setPreferenceKey(UUID.randomUUID().toString());p.setCreatedAt(now());}
            else p.setVersion(next(p.getVersion()));
            p.setMinYear(input.minYear());p.setMaxYear(input.maxYear());p.setIncludeTracked(input.includeTracked());
            p.setExcludedTagsJson(json(cleanTags(input.excludedTags(),50)));p.setRecallTagsJson(json(cleanTags(input.recallTags(),3)));p.setUpdatedAt(now());
            return settings(preferences.saveAndFlush(p));
        });
    }
    public Snapshot snapshot() { return read.execute(s -> snapshotValue()); }
    public Snapshot snapshotValue() {
        Settings settings=settingsValue();
        var tracked=new TreeSet<Integer>();var names=new TreeSet<String>();var watched=new TreeSet<Integer>();
        var blocked=new TreeSet<Integer>();var rejected=new TreeSet<Integer>();var recent=new TreeSet<Integer>();
        var counts=new TreeMap<String,Integer>();var library=new ArrayList<Object>();
        for(var a:anime.findAll(Sort.by("id"))) {
            names.add(RecommendationPolicy.normalize(a.getName()));if(a.getBangumiId()!=null) tracked.add(a.getBangumiId());
            if(a.getStatus()==AnimeStatus.FINISHED&&a.getBangumiId()!=null)watched.add(a.getBangumiId());
            var tags=a.getTags().stream().map(Tag::getName).sorted().toList();
            library.add(Arrays.asList(a.getId(),a.getName(),a.getBangumiId(),a.getStatus(),a.getScore(),tags));
            if(a.getScore()!=null&&Double.isFinite(a.getScore())&&a.getScore()>=7&&a.getScore()<=10&&a.getStatus()!=AnimeStatus.DROPPED)
                for(String tag:tags)counts.merge(tag,1,Integer::sum);
        }
        var mirrors=new ArrayList<Object>();
        for(var e:external.findAll(Sort.by("id"))) {
            mirrors.add(Arrays.asList(e.getCollectionKey(),e.getSubjectId(),e.getCollectionType(),e.getVersion()));
            if(Integer.valueOf(2).equals(e.getCollectionType()))watched.add(e.getSubjectId());
            if(e.getCollectionType()!=null&&Set.of(3,4,5).contains(e.getCollectionType()))blocked.add(e.getSubjectId());
        }
        var entries=feedback.findAll(Sort.by("id")).stream().map(this::entry).toList();
        for(var f:entries)if(f.active()) {if(f.kind()==Kind.NOT_INTERESTED)rejected.add(f.subjectId());else watched.add(f.subjectId());}
        for(var e:shown.findByShownAtAfter(now().minusDays(7)))recent.add(e.getSubjectId());
        List<String> seeds=settings.recallTags().isEmpty()?counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(3).map(Map.Entry::getKey).toList():settings.recallTags();
        var basisPage=bases.pageValue();
        return new Snapshot(settings,seeds,tracked,names,watched,blocked,rejected,recent,hash(json(Arrays.asList(settings,library,mirrors,entries,basisPage))),hash(json(Arrays.asList(settings,library,mirrors,basisPage))),basisPage);
    }
    public FeedbackPage feedbackPage(int page,int size) {
        if(page<0||size<1||size>50)throw new IllegalArgumentException("反馈分页无效");
        return read.execute(s -> {
            var all=feedback.findAll(Sort.by(Sort.Direction.DESC,"id")).stream().filter(RecommendationFeedback::isActive).map(this::entry).toList();
            int start=(int)Math.min((long)page*size,all.size());return new FeedbackPage(all.subList(start,Math.min(start+size,all.size())),page,all.size());
        });
    }
    public FeedbackEntry previous(String owner,FeedbackRequest request) {
        var previous=feedback.findByFeedbackKey(request.requestKey()).orElse(null);
        if(previous==null)return null;
        if(!previous.getOwner().equals(owner)||!previous.getSourceBatchKey().equals(request.batchKey())||!previous.getSubjectId().equals(request.subjectId())||previous.getKind()!=request.kind())
            throw new ConflictException("同一反馈请求内容或身份不同");
        return entry(previous);
    }
    public FeedbackEntry createFeedback(String owner,FeedbackRequest request,Candidate candidate) {
        if(feedback.count()>=100000)throw new IllegalArgumentException("反馈历史达到100000条上限，请先备份核对");
        if(feedback.existsBySubjectIdAndActiveTrue(request.subjectId()))throw new ConflictException("作品已有反馈，请刷新或先撤销");
        var f=new RecommendationFeedback();f.setFeedbackKey(request.requestKey());f.setOwner(owner);f.setSourceBatchKey(request.batchKey());
        f.setSubjectId(candidate.id());f.setName(candidate.nameCn().isBlank()?candidate.name():candidate.nameCn());f.setKind(request.kind());f.setCreatedAt(now());f.setUpdatedAt(now());
        return entry(feedback.saveAndFlush(f));
    }
    public void remove(String key,long version) {
        BangumiImportPreviewService.uuid(key);
        locked(() -> {var f=feedback.findByFeedbackKey(key).orElseThrow(() -> new ResourceNotFoundException("反馈不存在"));
            if(!f.getVersion().equals(version)||!f.isActive())throw new ConflictException("反馈已变化，请刷新后撤销");
            f.setActive(false);f.setVersion(next(f.getVersion()));f.setUpdatedAt(now());feedback.saveAndFlush(f);return null;});
    }
    public void shown(String batchKey,LocalDateTime at,List<Integer> ids) {
        shown.deleteByShownAtBefore(now().minusDays(7));
        if(shown.count()+ids.size()>100000)throw new IllegalArgumentException("近期记录达到保护上限");
        for(int id:ids){var s=shown.findById(id).orElseGet(RecommendationShown::new);
            if(s.getShownAt()!=null&&!s.getShownAt().isBefore(at))continue;
            s.setSubjectId(id);s.setShownAt(at);s.setBatchKey(batchKey);shown.save(s);}
        shown.flush();
    }
    public <T> T locked(Supplier<T> action) { return write.execute(s -> {locks.lockSingleton().orElseThrow(() -> new IllegalStateException("导入锁缺失"));return action.get();}); }
    public FeedbackEntry entry(RecommendationFeedback f) {return new FeedbackEntry(f.getFeedbackKey(),f.getOwner(),f.getSourceBatchKey(),f.getSubjectId(),f.getName(),f.getKind(),f.isActive(),f.getVersion(),f.getCreatedAt(),f.getUpdatedAt());}
    public LocalDateTime now(){return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);}
    public static long next(Long version){if(version==null||version==Long.MAX_VALUE)throw new IllegalArgumentException("推荐版本达到上限");return version+1;}
    public static void owner(String owner){if(owner==null||owner.isBlank()||owner.length()>100)throw new IllegalArgumentException("登录身份无效");}
    public static void validate(Settings s,boolean stored){
        if(s==null||s.version()==null||s.version()<0)throw new IllegalArgumentException("推荐条件版本无效");
        if(stored||s.key()!=null)BangumiImportPreviewService.uuid(s.key());
        if((s.minYear()!=null&&(s.minYear()<1||s.minYear()>9999))||(s.maxYear()!=null&&(s.maxYear()<1||s.maxYear()>9999))||(s.minYear()!=null&&s.maxYear()!=null&&s.minYear()>s.maxYear()))throw new IllegalArgumentException("年份须为1至9999，起始年份不能大于结束年份");
        cleanTags(s.excludedTags(),50);cleanTags(s.recallTags(),3);
    }
    public static List<String> cleanTags(List<String> tags,int limit){
        if(tags==null||tags.size()>limit)throw new IllegalArgumentException("标签数组缺失或数量超限");
        var seen=new HashSet<String>();var result=new ArrayList<String>();
        for(String tag:tags){if(tag==null||tag.isBlank()||tag.length()>200||tag.codePoints().anyMatch(Character::isISOControl)||RecommendationPolicy.normalize(tag).length()>200)throw new IllegalArgumentException("标签须为1至200字，不含控制字符");
            if(!seen.add(RecommendationPolicy.normalize(tag)))throw new IllegalArgumentException("标签重复，请合并后保存");result.add(tag.strip());}
        return List.copyOf(result);
    }
    private List<String> tags(String json){try{return mapper.readValue(json,mapper.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){throw new IllegalStateException("本地推荐条件格式无效",e);}}
    public String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("推荐数据编码失败",e);}}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
