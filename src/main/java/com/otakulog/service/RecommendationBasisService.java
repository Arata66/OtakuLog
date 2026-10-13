package com.otakulog.service;

import com.otakulog.common.*;
import com.otakulog.dto.RecommendationBasisDTO.*;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.entity.RecommendationBasis;
import com.otakulog.enums.InsightDirection;
import com.otakulog.repository.*;
import jakarta.persistence.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RecommendationBasisService {
    private final RecommendationBasisRepository bases;
    private final TasteProfileService profile;
    private final MemoryInsightRepository insights;
    private final AnimeMemoryRepository memories;
    private final AnimeRepository anime;
    private final ImportLockRepository locks;
    private final BangumiService bangumi;
    private final TransactionTemplate read,write;
    @PersistenceContext private EntityManager entityManager;
    public RecommendationBasisService(RecommendationBasisRepository bases,TasteProfileService profile,MemoryInsightRepository insights,
            AnimeMemoryRepository memories,AnimeRepository anime,ImportLockRepository locks,BangumiService bangumi,PlatformTransactionManager manager) {
        this.bases=bases;this.profile=profile;this.insights=insights;this.memories=memories;this.anime=anime;this.locks=locks;this.bangumi=bangumi;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Page get(){return read.execute(s -> pageValue());}
    public Page pageValue(){var sources=sourcesValue();var byKey=new HashMap<String,Source>();sources.forEach(s -> byKey.put(s.insightKey(),s));
        return new Page(sources,bases.findAll(Sort.by("id")).stream().map(b -> entry(b,byKey.get(b.getInsightKey()))).toList());}
    private List<Source> sourcesValue(){var result=new ArrayList<Source>();
        for(var g:profile.get().groups()) {
            for(var e:g.likes())result.add(new Source(e.insightKey(),e.sourceVersion(),e.insightVersion(),e.sourceFingerprint(),InsightDirection.LIKE,e.animeId(),e.memoryId(),e.animeName(),g.factor(),e.statement(),e.quote()));
            for(var e:g.dislikes())result.add(new Source(e.insightKey(),e.sourceVersion(),e.insightVersion(),e.sourceFingerprint(),InsightDirection.DISLIKE,e.animeId(),e.memoryId(),e.animeName(),g.factor(),e.statement(),e.quote()));
        }
        return List.copyOf(result);
    }
    public Preview preview(Write input){validate(input);read.execute(s -> checkedSource(input));
        if(input.kind()==Kind.SEED){var seed=seed(input.subjectId());return new Preview("喜欢：口味模式使用这部作品的实际标签召回，最多采用3个不同标签；原有硬条件继续有效。",display(seed),seed.getAllTags().stream().limit(3).toList());}
        return new Preview(input.direction()==InsightDirection.LIKE?"喜欢：口味模式按标签「"+input.tag().strip()+"」召回并核对。":"不喜欢：仅在口味模式避开实际包含「"+input.tag().strip()+"」的作品；同标签喜欢与避开会提示冲突。","",List.of(input.tag().strip()));
    }
    public Entry save(Write input){validate(input);
        var previous=read.execute(s -> bases.findByBasisKey(input.key()).filter(b -> retry(b,input)).map(b -> entry(b,current(input.insightKey()))).orElse(null));
        if(previous!=null)return previous;
        preview(input);
        return write.execute(s -> {locks.lockSingleton().orElseThrow(() -> new IllegalStateException("导入锁缺失"));
            var i=insights.findByInsightKey(input.insightKey()).orElseThrow(() -> new ConflictException("来源观点已缺失，请重新核对"));
            var m=memories.findById(i.getMemoryId()).orElseThrow(() -> new ConflictException("来源记忆已缺失，请重新核对"));
            anime.findByIdForUpdate(m.getAnimeId()).orElseThrow(() -> new ConflictException("来源作品已缺失"));entityManager.clear();
            var source=checkedSource(input);var b=bases.findByBasisKey(input.key()).orElse(null);
            if(b!=null&&retry(b,input))return entry(b,source);
            if(b!=null&&(!b.getInsightKey().equals(input.insightKey())||!b.getVersion().equals(input.expectedVersion())))throw new ConflictException("关联版本或身份已变化，请重新读取");
            if(b==null){if(input.expectedVersion()!=0||bases.findByInsightKey(input.insightKey()).isPresent())throw new ConflictException("观点已有其他编号的关联，请重新读取");
                if(bases.count()>=1000)throw new IllegalArgumentException("推荐关联达到1000条上限");b=new RecommendationBasis();b.setBasisKey(input.key());b.setInsightKey(input.insightKey());b.setCreatedAt(now());}
            else b.setVersion(RecommendationState.next(b.getVersion()));
            b.setSourceVersion(input.sourceVersion());b.setInsightVersion(input.insightVersion());b.setSourceFingerprint(input.sourceFingerprint());b.setDirection(input.direction());
            b.setKind(input.kind());b.setTag(input.tag()==null?null:input.tag().strip());b.setSubjectId(input.subjectId());b.setEnabled(input.enabled());b.setUpdatedAt(now());
            bases.saveAndFlush(b);entityManager.refresh(b);return entry(b,source);
        });
    }
    public void disable(String key,long expectedVersion){BangumiImportPreviewService.uuid(key);if(expectedVersion<0)throw new IllegalArgumentException("关联版本无效");
        write.execute(s -> {locks.lockSingleton().orElseThrow(() -> new IllegalStateException("导入锁缺失"));var b=bases.findByBasisKey(key).orElseThrow(() -> new ResourceNotFoundException("关联不存在"));
            if(!b.isEnabled()&&expectedVersion<Long.MAX_VALUE&&b.getVersion()==expectedVersion+1)return null;
            if(!b.getVersion().equals(expectedVersion))throw new ConflictException("关联已变化，请刷新后停用");
            if(b.isEnabled()){b.setEnabled(false);b.setVersion(RecommendationState.next(b.getVersion()));b.setUpdatedAt(now());bases.saveAndFlush(b);}return null;});}
    private Source current(String key){return sourcesValue().stream().filter(s -> s.insightKey().equals(key)).findFirst().orElse(null);}
    private Source checkedSource(Write input){var source=current(input.insightKey());
        if(source==null||!source.sourceVersion().equals(input.sourceVersion())||!source.insightVersion().equals(input.insightVersion())||!source.sourceFingerprint().equals(input.sourceFingerprint())||source.direction()!=input.direction())throw new ConflictException("观点或原文已变化，请重新核对来源");return source;}
    private boolean retry(RecommendationBasis b,Write i){return (b.getVersion().equals(i.expectedVersion())||(i.expectedVersion()<Long.MAX_VALUE&&b.getVersion()==i.expectedVersion()+1))
            &&b.getInsightKey().equals(i.insightKey())&&b.getSourceVersion().equals(i.sourceVersion())&&b.getInsightVersion().equals(i.insightVersion())&&b.getSourceFingerprint().equals(i.sourceFingerprint())
            &&b.getDirection()==i.direction()&&b.getKind()==i.kind()&&Objects.equals(b.getTag(),i.tag()==null?null:i.tag().strip())&&Objects.equals(b.getSubjectId(),i.subjectId())&&b.isEnabled()==i.enabled();}
    public Entry entry(RecommendationBasis b,Source source){var data=stored(b);boolean active=source!=null&&source.sourceVersion().equals(data.sourceVersion())&&source.insightVersion().equals(data.insightVersion())&&source.sourceFingerprint().equals(data.sourceFingerprint())&&source.direction()==data.direction();
        return new Entry(data,!data.enabled()?"DISABLED":active?"ACTIVE":"STALE",source);}
    public Stored stored(RecommendationBasis b){return new Stored(b.getBasisKey(),b.getInsightKey(),b.getSourceVersion(),b.getInsightVersion(),b.getSourceFingerprint(),b.getDirection(),b.getKind(),b.getTag(),b.getSubjectId(),b.isEnabled(),b.getVersion(),b.getCreatedAt(),b.getUpdatedAt());}
    public BangumiSubjectDetail seed(int id){var d=bangumi.getSubject(id);if(d==null||d.getId()!=id||!Integer.valueOf(2).equals(d.getType())||d.getName()==null||d.getName().isBlank()||d.getName().length()>1000||d.getAllTags()==null||d.getAllTags().isEmpty()||d.getAllTags().size()>500||d.getAllTags().stream().anyMatch(t -> t==null||t.isBlank()||t.length()>200))throw new IllegalArgumentException("作品必须是已核对且有实际标签的动画");return d;}
    public static String display(BangumiSubjectDetail d){return d.getNameCn()!=null&&!d.getNameCn().isBlank()&&d.getNameCn().length()<=1000?d.getNameCn():d.getName();}
    public static void validate(Write i){if(i==null)throw new IllegalArgumentException("关联字段缺失");BangumiImportPreviewService.uuid(i.key());BangumiImportPreviewService.uuid(i.insightKey());
        if(i.sourceVersion()==null||i.sourceVersion()<0||i.insightVersion()==null||i.insightVersion()<0||i.expectedVersion()==null||i.expectedVersion()<0||i.sourceFingerprint()==null||!i.sourceFingerprint().matches("[0-9a-f]{64}")||i.direction()==null||i.kind()==null)throw new IllegalArgumentException("关联来源、指纹或版本无效");
        if(i.kind()==Kind.TAG){if(i.subjectId()!=null||i.tag()==null)throw new IllegalArgumentException("标签关联不能带作品编号");RecommendationState.cleanTags(List.of(i.tag()),1);}
        else if(i.tag()!=null||i.subjectId()==null||i.subjectId()<1||i.direction()!=InsightDirection.LIKE)throw new IllegalArgumentException("只有喜欢观点可关联一部作品，不喜欢请明确关联避开标签");}
    private LocalDateTime now(){return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);}
}
