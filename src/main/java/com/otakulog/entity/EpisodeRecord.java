package com.otakulog.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "episode_record", uniqueConstraints = {
    @UniqueConstraint(name = "uk_anime_episode", columnNames = {"anime_id", "episode_number"})
}, indexes = {
    @Index(name = "idx_er_watched_date", columnList = "watched_date")
})
public class EpisodeRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anime_id", nullable = false)
    private Long animeId;

    @Column(name = "episode_number", nullable = false)
    private Integer episodeNumber;

    @Column(name = "watched_date", nullable = false)
    private LocalDate watchedDate;

    public EpisodeRecord() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAnimeId() { return animeId; }
    public void setAnimeId(Long animeId) { this.animeId = animeId; }

    public Integer getEpisodeNumber() { return episodeNumber; }
    public void setEpisodeNumber(Integer episodeNumber) { this.episodeNumber = episodeNumber; }

    public LocalDate getWatchedDate() { return watchedDate; }
    public void setWatchedDate(LocalDate watchedDate) { this.watchedDate = watchedDate; }
}
