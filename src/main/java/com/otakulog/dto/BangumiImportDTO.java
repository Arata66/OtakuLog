package com.otakulog.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class BangumiImportDTO {
    private BangumiImportDTO() {}
    public record ReadRequest(String username) {}
    public record ConfirmRequest(String previewKey, String requestKey, boolean importScores, boolean importTags) {}
    public record CollectionData(Integer subjectId, String name, String nameCn, Integer type, Integer eps,
            Integer epStatus, Integer rate, List<String> tags, String comment, String date, String image,
            String sourceUpdatedAt) {}
    public record Row(CollectionData source, String result, String reason) {}
    public record Preview(String previewKey, String username, int total, int readCount, boolean complete,
            boolean sourceChanged, Instant expiresAt, int newCount, int existingCount, int reviewCount, List<Row> rows) {}
    public record Result(String requestKey, int created, int skipped, int needsReview, int total, int externalSaved) {}
    public record ExternalRecord(String key, String username, CollectionData source, long version,
            LocalDateTime capturedAt) {}
}
