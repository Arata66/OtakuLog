package com.otakulog.entity;

import com.otakulog.enums.AnimeStatus;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "anime", indexes = {
    @Index(name = "idx_anime_name", columnList = "name"),
    @Index(name = "idx_anime_status", columnList = "status"),
    @Index(name = "idx_anime_broadcast_day", columnList = "broadcast_day")
})
public class Anime extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "current_episode")
    private Integer currentEpisode;

    @Column(name = "total_episodes")
    private Integer totalEpisodes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AnimeStatus status;

    @Column(name = "score")
    private Double score;

    @Column(name = "season")
    private String season;

    @Column(name = "remark")
    private String remark;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "broadcast_day")
    private Integer broadcastDay;  // 1=周一..7=周日

    @Column(name = "bangumi_id")
    private Integer bangumiId;

    public Anime() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getCurrentEpisode() {
        return currentEpisode;
    }

    public void setCurrentEpisode(Integer currentEpisode) {
        this.currentEpisode = currentEpisode;
    }

    public Integer getTotalEpisodes() {
        return totalEpisodes;
    }

    public void setTotalEpisodes(Integer totalEpisodes) {
        this.totalEpisodes = totalEpisodes;
    }

    public AnimeStatus getStatus() {
        return status;
    }

    public void setStatus(AnimeStatus status) {
        this.status = status;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
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

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getBroadcastDay() {
        return broadcastDay;
    }

    public void setBroadcastDay(Integer broadcastDay) {
        this.broadcastDay = broadcastDay;
    }

    public Integer getBangumiId() {
        return bangumiId;
    }

    public void setBangumiId(Integer bangumiId) {
        this.bangumiId = bangumiId;
    }
}
