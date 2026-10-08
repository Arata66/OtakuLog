package com.otakulog.dto;

import java.util.List;
import java.util.Map;

public class AnnualReportDTO {

    private int year;
    private long totalWatched;
    private long totalEpisodes;
    private double averageRating;
    private double watchingHours;
    private long watchedAnimeCount;
    private long ratedAnimeCount;
    private long legacyDatedEpisodes;
    private long undatedEpisodeRecords;
    private long missingEpisodeRecords;
    private long undatedFinishedAnimeCount;
    private int minutesPerEpisode;
    private boolean watchingHoursEstimated;
    private List<Map<String, Object>> monthlyStats;
    private List<Map<String, Object>> topAnimes;
    private List<Map<String, Object>> tagDistribution;
    private List<Map<String, Object>> ratingDistribution;

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public long getTotalWatched() {
        return totalWatched;
    }

    public void setTotalWatched(long totalWatched) {
        this.totalWatched = totalWatched;
    }

    public long getTotalEpisodes() {
        return totalEpisodes;
    }

    public void setTotalEpisodes(long totalEpisodes) {
        this.totalEpisodes = totalEpisodes;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public double getWatchingHours() {
        return watchingHours;
    }

    public void setWatchingHours(double watchingHours) {
        this.watchingHours = watchingHours;
    }

    public long getWatchedAnimeCount() { return watchedAnimeCount; }
    public void setWatchedAnimeCount(long value) { this.watchedAnimeCount = value; }
    public long getRatedAnimeCount() { return ratedAnimeCount; }
    public void setRatedAnimeCount(long value) { this.ratedAnimeCount = value; }
    public long getLegacyDatedEpisodes() { return legacyDatedEpisodes; }
    public void setLegacyDatedEpisodes(long value) { this.legacyDatedEpisodes = value; }
    public long getUndatedEpisodeRecords() { return undatedEpisodeRecords; }
    public void setUndatedEpisodeRecords(long value) { this.undatedEpisodeRecords = value; }
    public long getMissingEpisodeRecords() { return missingEpisodeRecords; }
    public void setMissingEpisodeRecords(long value) { this.missingEpisodeRecords = value; }
    public long getUndatedFinishedAnimeCount() { return undatedFinishedAnimeCount; }
    public void setUndatedFinishedAnimeCount(long value) { this.undatedFinishedAnimeCount = value; }
    public int getMinutesPerEpisode() { return minutesPerEpisode; }
    public void setMinutesPerEpisode(int value) { this.minutesPerEpisode = value; }
    public boolean isWatchingHoursEstimated() { return watchingHoursEstimated; }
    public void setWatchingHoursEstimated(boolean value) { this.watchingHoursEstimated = value; }

    public List<Map<String, Object>> getMonthlyStats() {
        return monthlyStats;
    }

    public void setMonthlyStats(List<Map<String, Object>> monthlyStats) {
        this.monthlyStats = monthlyStats;
    }

    public List<Map<String, Object>> getTopAnimes() {
        return topAnimes;
    }

    public void setTopAnimes(List<Map<String, Object>> topAnimes) {
        this.topAnimes = topAnimes;
    }

    public List<Map<String, Object>> getTagDistribution() {
        return tagDistribution;
    }

    public void setTagDistribution(List<Map<String, Object>> tagDistribution) {
        this.tagDistribution = tagDistribution;
    }

    public List<Map<String, Object>> getRatingDistribution() {
        return ratingDistribution;
    }

    public void setRatingDistribution(List<Map<String, Object>> ratingDistribution) {
        this.ratingDistribution = ratingDistribution;
    }
}
