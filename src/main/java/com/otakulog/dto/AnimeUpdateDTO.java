package com.otakulog.dto;

import jakarta.validation.constraints.*;

public class AnimeUpdateDTO {

    @NotBlank(message = "番剧名称不能为空")
    @Size(max = 100, message = "名称长度不能超过100个字符")
    private String name;

    @Min(value = 1, message = "总集数不能小于1")
    private Integer totalEpisodes;

    @NotBlank(message = "季度不能为空")
    @Size(max = 50, message = "季度长度不能超过50个字符")
    private String season;

    @NotNull(message = "评分不能为空")
    @DecimalMin(value = "0", message = "评分不能小于0")
    @DecimalMax(value = "10", message = "评分不能大于10")
    private Double score;

    private String remark;

    private String coverUrl;

    private String startDate;

    private String endDate;

    private String tags;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTotalEpisodes() {
        return totalEpisodes;
    }

    public void setTotalEpisodes(Integer totalEpisodes) {
        this.totalEpisodes = totalEpisodes;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }
}
