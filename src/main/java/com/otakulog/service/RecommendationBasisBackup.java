package com.otakulog.service;

import com.otakulog.dto.BackupDocument;
import com.otakulog.dto.RecommendationBasisDTO.*;
import com.otakulog.entity.RecommendationBasis;
import com.otakulog.repository.RecommendationBasisRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RecommendationBasisBackup {
    private final RecommendationBasisRepository bases;private final RecommendationBasisService service;
    public RecommendationBasisBackup(RecommendationBasisRepository bases,RecommendationBasisService service){this.bases=bases;this.service=service;}
    public List<Stored> export(){return bases.findAll(Sort.by("id")).stream().map(service::stored).toList();}
    public void validate(BackupDocument doc){
        require(doc.recommendationBases()!=null&&doc.recommendationBases().size()<=1000,"推荐关联集合缺失或超限");
        var keys=new HashSet<String>();var sources=new HashSet<String>();
        for(var b:doc.recommendationBases()){
            RecommendationBasisService.validate(new Write(b.key(),b.insightKey(),b.sourceVersion(),b.insightVersion(),b.sourceFingerprint(),b.direction(),b.kind(),b.tag(),b.subjectId(),b.enabled(),b.version()));
            require(keys.add(b.key())&&sources.add(b.insightKey()),"关联编号或观点身份重复");
            require(b.tag()==null||b.tag().equals(b.tag().strip()),"关联标签须为保存后的规范形式");
            require(b.createdAt()!=null&&b.updatedAt()!=null&&b.createdAt().getYear()>=1000&&b.updatedAt().getYear()<=9999&&!b.updatedAt().isBefore(b.createdAt())&&b.createdAt().getNano()%1000==0&&b.updatedAt().getNano()%1000==0,"关联审计时间须为有效微秒精度");
        }
    }
    public void analyze(BackupDocument doc,List<String> conflicts,Map<String,Object> summary){int added=0;
        for(var b:doc.recommendationBases()){
            var local=bases.findByBasisKey(b.key()).orElse(null);
            if(local!=null){if(!service.stored(local).equals(b))conflicts.add("推荐关联 "+b.key()+" 的内容与本地不同");}
            else {if(bases.findByInsightKey(b.insightKey()).isPresent())conflicts.add("观点已有其他编号的推荐关联");added++;}
        }
        require(bases.count()+added<=1000,"合并关联超过1000条保护上限");summary.put("recommendationBases",doc.recommendationBases().size());summary.put("newRecommendationBases",added);
    }
    public void restore(BackupDocument doc){for(var b:doc.recommendationBases())if(bases.findByBasisKey(b.key()).isEmpty()){
        var value=new RecommendationBasis();value.setBasisKey(b.key());value.setInsightKey(b.insightKey());value.setSourceVersion(b.sourceVersion());value.setInsightVersion(b.insightVersion());value.setSourceFingerprint(b.sourceFingerprint());
        value.setDirection(b.direction());value.setKind(b.kind());value.setTag(b.tag());value.setSubjectId(b.subjectId());value.setEnabled(b.enabled());value.setVersion(b.version());value.setCreatedAt(b.createdAt());value.setUpdatedAt(b.updatedAt());bases.save(value);
    }}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
