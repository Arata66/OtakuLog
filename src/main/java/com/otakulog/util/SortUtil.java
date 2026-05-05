package com.otakulog.util;

import org.springframework.data.domain.Sort;

public class SortUtil {

    private SortUtil() {}

    public static Sort buildSort(String sortBy) {
        return switch (sortBy != null ? sortBy : "id-desc") {
            case "score-desc" -> Sort.by(Sort.Direction.DESC, "score");
            case "score-asc" -> Sort.by(Sort.Direction.ASC, "score");
            case "progress-desc" -> Sort.by(Sort.Direction.DESC, "currentEpisode");
            case "progress-asc" -> Sort.by(Sort.Direction.ASC, "currentEpisode");
            case "name-asc" -> Sort.by(Sort.Direction.ASC, "name");
            case "sortOrder-asc" -> Sort.by(Sort.Direction.ASC, "sortOrder");
            default -> Sort.by(Sort.Direction.DESC, "id");
        };
    }
}
