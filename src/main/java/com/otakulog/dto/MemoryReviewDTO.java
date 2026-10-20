package com.otakulog.dto;

import java.util.List;

public record MemoryReviewDTO(int page, int size, long totalEntries, List<Item> entries, List<Work> works) {
    public record Item(String animeName, AnimeMemoryDTO.Entry memory) {}
    public record Work(Long id, String name) {}
    public record Location(AnimeVO anime, Long memoryId, int page) {}
}
