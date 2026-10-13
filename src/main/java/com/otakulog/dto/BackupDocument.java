package com.otakulog.dto;

import com.otakulog.enums.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record BackupDocument(String format, Integer version, LocalDateTime exportedAt,
        List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups,
        List<Membership> memberships, List<EpisodeEntry> episodes, List<MemoryEntry> memories,
        List<InsightEntry> insights, List<AiCallEntry> aiCalls,
        List<ExternalEntry> externalCollections, List<ImportBatchEntry> importBatches,
        List<RecommendationDTO.PreferenceEntry> recommendationPreferences, List<RecommendationDTO.FeedbackEntry> recommendationFeedback) {
    public BackupDocument(String format,Integer version,LocalDateTime exportedAt,List<AnimeEntry> anime,List<TagEntry> tags,
            List<GroupEntry> groups,List<Membership> memberships,List<EpisodeEntry> episodes,List<MemoryEntry> memories,
            List<InsightEntry> insights,List<AiCallEntry> aiCalls,List<ExternalEntry> externalCollections,List<ImportBatchEntry> importBatches){
        this(format,version,exportedAt,anime,tags,groups,memberships,episodes,memories,insights,aiCalls,externalCollections,importBatches,List.of(),List.of());
    }
    public BackupDocument(String format, Integer version, LocalDateTime exportedAt,
            List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups, List<Membership> memberships,
            List<EpisodeEntry> episodes, List<MemoryEntry> memories, List<InsightEntry> insights, List<AiCallEntry> aiCalls) {
        this(format, version, exportedAt, anime, tags, groups, memberships, episodes, memories, insights, aiCalls, List.of(), List.of());
    }
    public record ExternalEntry(String key, String username, Integer subjectId, String payload, Long version,
            LocalDateTime capturedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record ImportBatchEntry(String requestKey, String owner, String username, String fingerprint, String resultJson,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public BackupDocument(String format, Integer version, LocalDateTime exportedAt,
            List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups,
            List<Membership> memberships, List<EpisodeEntry> episodes, List<MemoryEntry> memories) {
        this(format, version, exportedAt, anime, tags, groups, memberships, episodes, memories, List.of(), List.of());
    }
    public BackupDocument(String format, Integer version, LocalDateTime exportedAt,
            List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups,
            List<Membership> memberships, List<EpisodeEntry> episodes) {
        this(format, version, exportedAt, anime, tags, groups, memberships, episodes, List.of());
    }
    public record MemoryEntry(String key, String animeKey, String content, String liked, String disliked,
            String scope, MemoryContext context, LocalDate watchedDate, Long version,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record InsightEntry(String key, String memoryKey, Long sourceVersion, InsightQuoteField quoteField,
            String quote, String statement, InsightDirection direction, InsightAspect aspect, String factor,
            InsightStatus status, InsightOrigin origin, String aiCallKey, InsightQuoteField suggestedQuoteField,
            String suggestedQuote, String suggestedStatement, InsightDirection suggestedDirection,
            InsightAspect suggestedAspect, String suggestedFactor, Long version,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record AiCallEntry(String requestKey, String memoryKey, Long sourceVersion, String inputHash,
            String model, String promptVersion, Integer selectedCharacters, AiCallStatus status,
            AiErrorCategory errorCategory, Integer promptTokens, Integer completionTokens,
            Integer cachedTokens, Integer reasoningTokens, LocalDateTime createdAt,
            LocalDateTime updatedAt, LocalDateTime completedAt) {}
    public record AnimeEntry(String key, AnimeData data, List<String> tagKeys) {}
    public record AnimeData(String name, Integer currentEpisode, Integer totalEpisodes, AnimeStatus status,
            Double score, String season, String remark, String coverUrl, LocalDate startDate, LocalDate endDate,
            Integer sortOrder, Integer broadcastDay, Integer bangumiId, LocalDate watchStartDate,
            Boolean legacy, String watchSeason, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record TagEntry(String key, String name, LocalDateTime createdAt) {}
    public record GroupEntry(String key, String name, String description, String color, Integer sortOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Membership(String groupKey, String animeKey) {}
    public record EpisodeEntry(String animeKey, Integer episodeNumber, LocalDate watchedDate,
            EpisodeRecordSource source, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
