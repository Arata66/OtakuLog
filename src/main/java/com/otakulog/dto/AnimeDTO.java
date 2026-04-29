package com.otakulog.dto;

import jakarta.validation.constraints.*;

public class AnimeDTO {

    @NotBlank(message = "番剧名称不能为空")
    @Size(max = 100, message = "名称长度不能超过100个字符")
    private String name;

    @NotNull(message = "总集数不能为空")
    @Min(value = 1, message = "总集数至少为1")
    private Integer totalEpisodes;

    @NotBlank(message = "季度不能为空")
    @Size(max = 50, message = "季度长度不能超过50个字符")
    private String season;

    @NotNull(message = "评分不能为空")
    @DecimalMin(value = "0", message = "评分不能小于0")
    @DecimalMax(value = "10", message = "评分不能大于10")
    private Double score;

    private String remark;

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
}
