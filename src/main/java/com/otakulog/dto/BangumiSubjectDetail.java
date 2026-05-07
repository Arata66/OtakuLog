package com.otakulog.dto;

import java.util.List;
import java.util.Map;

public class BangumiSubjectDetail {
    private int id;
    private String name;
    private String nameCn;
    private String summary;
    private List<Map<String, Object>> tags;
    private Double rating;
    private Integer ratingCount;
    private Integer totalEpisodes;
    private String airDate;
    private String coverUrl;
    private String platform;
    private Map<String, Object> ratingDetails;
    private Integer rank;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameCn() { return nameCn; }
    public void setNameCn(String nameCn) { this.nameCn = nameCn; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<Map<String, Object>> getTags() { return tags; }
    public void setTags(List<Map<String, Object>> tags) { this.tags = tags; }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public Integer getRatingCount() { return ratingCount; }
    public void setRatingCount(Integer ratingCount) { this.ratingCount = ratingCount; }
    public Integer getTotalEpisodes() { return totalEpisodes; }
    public void setTotalEpisodes(Integer totalEpisodes) { this.totalEpisodes = totalEpisodes; }
    public String getAirDate() { return airDate; }
    public void setAirDate(String airDate) { this.airDate = airDate; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public Map<String, Object> getRatingDetails() { return ratingDetails; }
    public void setRatingDetails(Map<String, Object> ratingDetails) { this.ratingDetails = ratingDetails; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
}
