package com.otakulog.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "anime")
public class Anime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "current_episode")
    private Integer currentEpisode;

    @Column(name = "total_episodes")
    private Integer totalEpisodes;

    @Column(name = "status")
    private String status;

    @Column(name = "score")
    private Double score;

    @Column(name = "season")
    private String season;

    @Column(name = "remark")
    private String remark;

    // Constructors
    public Anime() {
    }

    public Anime(String name, Integer currentEpisode, Integer totalEpisodes, String status, Double score, String season, String remark) {
        this.name = name;
        this.currentEpisode = currentEpisode;
        this.totalEpisodes = totalEpisodes;
        this.status = status;
        this.score = score;
        this.season = season;
        this.remark = remark;
    }

    // Getters and Setters
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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
}
