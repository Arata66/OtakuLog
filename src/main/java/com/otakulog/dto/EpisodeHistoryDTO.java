package com.otakulog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.EpisodeRecordSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EpisodeHistoryDTO(Long animeId, int currentEpisode, int page, int size,
                                long totalRows, List<Entry> entries) {
    public record Entry(Integer episodeNumber, Boolean recorded, Long recordId, LocalDate watchedDate,
                        EpisodeRecordSource source, LocalDateTime updatedAt) {
        public static Entry from(int number, EpisodeRecord record) {
            return record == null ? new Entry(number, false, null, null, null, null)
                    : new Entry(number, true, record.getId(), record.getWatchedDate(), record.getSource(), record.getUpdatedAt());
        }
    }

    public record UpdateRequest(@JsonProperty(required = true) LocalDate watchedDate,
                                @JsonProperty(required = true) Entry expected) {}
}
