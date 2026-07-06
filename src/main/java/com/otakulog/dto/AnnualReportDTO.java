package com.otakulog.dto;

import java.util.List;
import java.util.Map;

public class AnnualReportDTO {

    private int year;
    private long totalWatched;
    private long totalEpisodes;
    private double averageRating;
    private double watchingHours;
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
