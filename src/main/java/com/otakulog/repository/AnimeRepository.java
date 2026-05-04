package com.otakulog.repository;

import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Sort;

import java.util.List;

@Repository
public interface AnimeRepository extends JpaRepository<Anime, Long> {

    List<Anime> findByNameContaining(String name);

    List<Anime> findByNameContaining(String name, Sort sort);

    List<Anime> findByStatus(AnimeStatus status);

    List<Anime> findByStatus(AnimeStatus status, Sort sort);

    List<Anime> findByNameContainingAndStatus(String name, AnimeStatus status);

    List<Anime> findByNameContainingAndStatus(String name, AnimeStatus status, Sort sort);

    long countByStatus(AnimeStatus status);

    // Paginated queries
    Page<Anime> findByNameContaining(String name, Pageable pageable);

    Page<Anime> findByStatus(AnimeStatus status, Pageable pageable);

    Page<Anime> findByNameContainingAndStatus(String name, AnimeStatus status, Pageable pageable);

    // Aggregation queries for stats
    @Query("SELECT COALESCE(SUM(a.totalEpisodes), 0) FROM Anime a")
    long sumTotalEpisodes();

    @Query("SELECT COALESCE(SUM(a.currentEpisode), 0) FROM Anime a")
    long sumCurrentEpisodes();

    @Query("SELECT COALESCE(AVG(a.score), 0.0) FROM Anime a WHERE a.score IS NOT NULL AND a.score > 0")
    Double averageScore();

    @Query("SELECT COUNT(a) FROM Anime a WHERE a.score IS NOT NULL AND a.score >= 8.0")
    long countHighScore();

    @Query("SELECT COUNT(a) FROM Anime a WHERE a.score IS NOT NULL AND a.score >= 6.0 AND a.score < 8.0")
    long countMediumScore();

    @Query("SELECT COUNT(a) FROM Anime a WHERE a.score IS NOT NULL AND a.score > 0 AND a.score < 6.0")
    long countLowScore();

    // Season grouping for timeline
    @Query("SELECT a.season, COUNT(a), AVG(a.score) FROM Anime a WHERE a.score IS NOT NULL GROUP BY a.season ORDER BY a.season DESC")
    List<Object[]> getSeasonStats();

    // Tag filtering
    @Query("SELECT a FROM Anime a WHERE a.tags LIKE CONCAT('%', :tag, '%')")
    List<Anime> findByTagContaining(@org.springframework.data.repository.query.Param("tag") String tag, Sort sort);

    @Query("SELECT a FROM Anime a WHERE a.tags LIKE CONCAT('%', :tag, '%')")
    Page<Anime> findByTagContaining(@org.springframework.data.repository.query.Param("tag") String tag, Pageable pageable);

    // Enhanced stats
    @Query("SELECT FUNCTION('YEAR', a.startDate) as yr, COUNT(a), AVG(a.score) FROM Anime a WHERE a.startDate IS NOT NULL AND a.score IS NOT NULL AND a.score > 0 GROUP BY yr ORDER BY yr DESC")
    List<Object[]> getYearlyStats();

    @Query("SELECT ROUND(a.score) as bucket, COUNT(a) FROM Anime a WHERE a.score IS NOT NULL AND a.score > 0 GROUP BY bucket ORDER BY bucket")
    List<Object[]> getScoreDistribution();

    List<Anime> findByBroadcastDay(Integer broadcastDay, Sort sort);
}
