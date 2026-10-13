package com.otakulog.dto;

import com.otakulog.enums.InsightDirection;
import java.time.LocalDateTime;
import java.util.List;

public final class RecommendationBasisDTO {
    private RecommendationBasisDTO() {}
    public enum Kind { TAG, SEED }
    public record Source(String insightKey,Long sourceVersion,Long insightVersion,String sourceFingerprint,InsightDirection direction,
            Long animeId,Long memoryId,String animeName,String factor,String statement,String quote) {}
    public record Write(String key,String insightKey,Long sourceVersion,Long insightVersion,String sourceFingerprint,InsightDirection direction,
            Kind kind,String tag,Integer subjectId,boolean enabled,Long expectedVersion) {}
    public record Stored(String key,String insightKey,Long sourceVersion,Long insightVersion,String sourceFingerprint,InsightDirection direction,
            Kind kind,String tag,Integer subjectId,boolean enabled,Long version,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    public record Entry(Stored data,String state,Source source) {}
    public record Page(List<Source> sources,List<Entry> entries) {}
    public record Preview(String effect,String subjectName,List<String> tags) {}
}
