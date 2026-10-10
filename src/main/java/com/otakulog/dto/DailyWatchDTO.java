package com.otakulog.dto;

import java.time.LocalDate;
import java.util.List;

public record DailyWatchDTO(LocalDate date, int weekday, int ongoingCount, Integer todayAiringCount,
                            List<Item> continueWatching, List<Item> todayAiring, String airingStatus) {
    public record Item(AnimeVO anime, LocalDate lastWatchedDate) {}
}
