package com.otakulog.dto;

import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.enums.MemoryContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record BackupDocument(String format, Integer version, LocalDateTime exportedAt,
        List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups,
        List<Membership> memberships, List<EpisodeEntry> episodes, List<MemoryEntry> memories) {
    public BackupDocument(String format, Integer version, LocalDateTime exportedAt,
            List<AnimeEntry> anime, List<TagEntry> tags, List<GroupEntry> groups,
            List<Membership> memberships, List<EpisodeEntry> episodes) {
        this(format, version, exportedAt, anime, tags, groups, memberships, episodes, List.of());
    }
    public record MemoryEntry(String key, String animeKey, String content, String liked, String disliked,
            String scope, MemoryContext context, LocalDate watchedDate, Long version,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
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
