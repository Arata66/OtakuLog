package com.otakulog.dto;

import java.time.LocalDate;
import java.util.List;

public record DailyWatchDTO(LocalDate date, int weekday, int ongoingCount, int todayAiringCount,
                            List<Item> continueWatching, List<Item> todayAiring) {
    public record Item(AnimeVO anime, LocalDate lastWatchedDate) {}
}
