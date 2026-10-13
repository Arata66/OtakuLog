package com.otakulog.service;

import com.otakulog.dto.BackupDocument;
import com.otakulog.dto.RecommendationDTO.*;
import com.otakulog.entity.*;
import com.otakulog.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RecommendationBackup {
    private final RecommendationPreferenceRepository preferences;
    private final RecommendationFeedbackRepository feedback;
    private final RecommendationState state;
    public RecommendationBackup(RecommendationPreferenceRepository preferences,RecommendationFeedbackRepository feedback,RecommendationState state){this.preferences=preferences;this.feedback=feedback;this.state=state;}
    public List<PreferenceEntry> preferences(){return preferences.findAll(Sort.by("id")).stream().map(this::entry).toList();}
    public List<FeedbackEntry> feedback(){return feedback.findAll(Sort.by("id")).stream().map(state::entry).toList();}
    public void validate(BackupDocument doc){
        require(doc.recommendationPreferences()!=null&&doc.recommendationFeedback()!=null,"推荐集合缺失");
        require(doc.recommendationPreferences().size()<=1&&doc.recommendationFeedback().size()<=100000,"推荐集合数量超限");
        for(var p:doc.recommendationPreferences()){
            RecommendationState.validate(p.settings(),true);audit(p.createdAt(),p.updatedAt());
            require(RecommendationState.cleanTags(p.settings().excludedTags(),50).equals(p.settings().excludedTags())&&RecommendationState.cleanTags(p.settings().recallTags(),3).equals(p.settings().recallTags()),"推荐标签须为规范化保存形式");
        }
        var keys=new HashSet<String>();var active=new HashSet<Integer>();
        for(var f:doc.recommendationFeedback()){
            BangumiImportPreviewService.uuid(f.key());BangumiImportPreviewService.uuid(f.sourceBatchKey());RecommendationState.owner(f.owner());
            require(keys.add(f.key())&&f.subjectId()!=null&&f.subjectId()>0,"反馈编号重复或作品编号无效");
            require(f.name()!=null&&!f.name().isBlank()&&f.name().length()<=1000&&f.kind()!=null&&f.version()!=null&&f.version()>=0,"反馈资料或版本无效");
            require(!f.active()||active.add(f.subjectId()),"同一作品不能有两条活动反馈");audit(f.createdAt(),f.updatedAt());
        }
    }
    public void analyze(BackupDocument doc,List<String> conflicts,Map<String,Object> summary){
        int addedPreferences=0,addedFeedback=0;
        var local=preferences.findById(1L).orElse(null);
        for(var p:doc.recommendationPreferences()){if(local==null)addedPreferences++;else if(!entry(local).equals(p))conflicts.add("推荐条件的编号或内容与本地不同");}
        var active=new HashSet<Integer>();for(var f:feedback.findAll())if(f.isActive())active.add(f.getSubjectId());
        for(var f:doc.recommendationFeedback()){
            var current=feedback.findByFeedbackKey(f.key()).orElse(null);
            if(current!=null){if(!state.entry(current).equals(f))conflicts.add("推荐反馈 "+f.key()+" 的内容与本地不同");continue;}
            if(f.active()&&!active.add(f.subjectId()))conflicts.add("作品 "+f.subjectId()+" 已有不同编号的活动反馈");addedFeedback++;
        }
        require(feedback.count()+addedFeedback<=100000,"合并反馈历史超过保护上限");
        summary.put("recommendationPreferences",doc.recommendationPreferences().size());summary.put("newRecommendationPreferences",addedPreferences);
        summary.put("recommendationFeedback",doc.recommendationFeedback().size());summary.put("newRecommendationFeedback",addedFeedback);
    }
    public void restore(BackupDocument doc){
        if(!doc.recommendationPreferences().isEmpty()&&!preferences.existsById(1L)){
            var e=doc.recommendationPreferences().get(0);var s=e.settings();var p=new RecommendationPreference();p.setId(1L);p.setPreferenceKey(s.key());p.setVersion(s.version());
            p.setMinYear(s.minYear());p.setMaxYear(s.maxYear());p.setIncludeTracked(s.includeTracked());p.setExcludedTagsJson(state.json(s.excludedTags()));p.setRecallTagsJson(state.json(s.recallTags()));
            p.setCreatedAt(e.createdAt());p.setUpdatedAt(e.updatedAt());preferences.save(p);
        }
        for(var e:doc.recommendationFeedback())if(feedback.findByFeedbackKey(e.key()).isEmpty()){
            var f=new RecommendationFeedback();f.setFeedbackKey(e.key());f.setOwner(e.owner());f.setSourceBatchKey(e.sourceBatchKey());f.setSubjectId(e.subjectId());f.setName(e.name());
            f.setKind(e.kind());f.setActive(e.active());f.setVersion(e.version());f.setCreatedAt(e.createdAt());f.setUpdatedAt(e.updatedAt());feedback.save(f);
        }
    }
    private PreferenceEntry entry(RecommendationPreference p){return new PreferenceEntry(state.settings(p),p.getCreatedAt(),p.getUpdatedAt());}
    private static void audit(LocalDateTime created,LocalDateTime updated){require(created!=null&&updated!=null&&created.getYear()>=1000&&updated.getYear()<=9999&&!updated.isBefore(created)&&created.getNano()%1000==0&&updated.getNano()%1000==0,"推荐审计时间须为有效微秒精度");}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
