package com.otakulog.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class RecommendationDTO {
    private RecommendationDTO() {}
    public enum Kind { NOT_INTERESTED, WATCHED }
    public record Settings(String key, Long version, Integer minYear, Integer maxYear, List<String> excludedTags, List<String> recallTags, boolean includeTracked) {}
    public record Candidate(int id, String name, String nameCn, String image, Double score, String date, Integer year,
            List<String> tags, String reason, boolean tracked, boolean watched, boolean recent) {}
    public record Result(Settings settings, List<String> seedTags, List<Candidate> entries, String batchKey, String status,
            Map<String,Integer> excludedCounts, int sourceErrors, int unverified) {}
    public record FeedbackRequest(String requestKey, String batchKey, Integer subjectId, Kind kind) {}
    public record FeedbackEntry(String key, String owner, String sourceBatchKey, Integer subjectId, String name, Kind kind,
            boolean active, Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record FeedbackPage(List<FeedbackEntry> entries, int page, long total) {}
    public record ShownRequest(String batchKey, List<Integer> subjectIds) {}
    public record PreferenceEntry(Settings settings, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
