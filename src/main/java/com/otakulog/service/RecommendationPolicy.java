package com.otakulog.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class RecommendationPolicy {
    private RecommendationPolicy() {}
    public static String normalize(String value) { return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    public static String evaluate(Integer year, List<String> tags, Integer min, Integer max, List<String> excluded,
            boolean tracked, boolean watched, boolean rejected, boolean includeTracked, boolean recent, boolean includeRecent) {
        if (rejected) return "FEEDBACK";
        if (!includeTracked && tracked) return "TRACKED";
        if (!includeTracked && watched) return "WATCHED";
        if ((min != null || max != null) && year == null) return "UNKNOWN_YEAR";
        if (year != null && ((min != null && year < min) || (max != null && year > max))) return "YEAR";
        if (!excluded.isEmpty() && tags == null) return "UNVERIFIED_TAGS";
        if (tags != null && tags.stream().map(RecommendationPolicy::normalize).anyMatch(t -> excluded.stream().map(RecommendationPolicy::normalize).anyMatch(t::equals))) return "TAG";
        if (!includeRecent && recent) return "RECENT";
        return null;
    }
}
