package com.otakulog.repository;

import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // Tag filtering — FIND_IN_SET 精确匹配，避免 LIKE 的子串误匹配
    @Query(value = "SELECT * FROM anime a WHERE FIND_IN_SET(:tag, a.tags) > 0",
            countQuery = "SELECT COUNT(*) FROM anime a WHERE FIND_IN_SET(:tag, a.tags) > 0",
            nativeQuery = true)
    List<Anime> findByTagContaining(@org.springframework.data.repository.query.Param("tag") String tag, Sort sort);

    @Query(value = "SELECT * FROM anime a WHERE FIND_IN_SET(:tag, a.tags) > 0",
            countQuery = "SELECT COUNT(*) FROM anime a WHERE FIND_IN_SET(:tag, a.tags) > 0",
            nativeQuery = true)
    Page<Anime> findByTagContaining(@org.springframework.data.repository.query.Param("tag") String tag, Pageable pageable);

    // Merged stats: returns [total, watching, finished, planning, dropped, sumTotal, sumCurrent, avgScore, highScore, midScore, lowScore]
    @Query("SELECT " +
            "COUNT(a), " +
            "SUM(CASE WHEN a.status = 'WATCHING' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.status = 'FINISHED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.status = 'PLANNING' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.status = 'DROPPED' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(a.totalEpisodes), 0), " +
            "COALESCE(SUM(a.currentEpisode), 0), " +
            "COALESCE(AVG(CASE WHEN a.score IS NOT NULL AND a.score > 0 THEN a.score END), 0.0), " +
            "SUM(CASE WHEN a.score IS NOT NULL AND a.score >= 8.0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.score IS NOT NULL AND a.score >= 6.0 AND a.score < 8.0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.score IS NOT NULL AND a.score > 0 AND a.score < 6.0 THEN 1 ELSE 0 END) " +
            "FROM Anime a")
    Object[] getAggregatedStats();

    // Batch update status
    @Modifying
    @Query("UPDATE Anime a SET a.status = :status WHERE a.id IN :ids")
    int batchUpdateStatusByIds(@org.springframework.data.repository.query.Param("ids") List<Long> ids,
                               @org.springframework.data.repository.query.Param("status") AnimeStatus status);

    // Batch update status to FINISHED with endDate
    @Modifying
    @Query("UPDATE Anime a SET a.status = 'FINISHED', a.endDate = CURRENT_DATE WHERE a.id IN :ids")
    int batchFinishByIds(@org.springframework.data.repository.query.Param("ids") List<Long> ids);

    // Batch update sortOrder
    @Modifying
    @Query("UPDATE Anime a SET a.sortOrder = CASE a.id " +
            "WHEN :id0 THEN :order0 " +
            "WHEN :id1 THEN :order1 " +
            "WHEN :id2 THEN :order2 " +
            "WHEN :id3 THEN :order3 " +
            "WHEN :id4 THEN :order4 " +
            "WHEN :id5 THEN :order5 " +
            "WHEN :id6 THEN :order6 " +
            "WHEN :id7 THEN :order7 " +
            "WHEN :id8 THEN :order8 " +
            "WHEN :id9 THEN :order9 " +
            "WHEN :id10 THEN :order10 " +
            "WHEN :id11 THEN :order11 " +
            "WHEN :id12 THEN :order12 " +
            "WHEN :id13 THEN :order13 " +
            "WHEN :id14 THEN :order14 " +
            "WHEN :id15 THEN :order15 " +
            "WHEN :id16 THEN :order16 " +
            "WHEN :id17 THEN :order17 " +
            "WHEN :id18 THEN :order18 " +
            "WHEN :id19 THEN :order19 END " +
            "WHERE a.id IN :ids")
    int batchUpdateSortOrder(@org.springframework.data.repository.query.Param("ids") List<Long> ids,
                             @org.springframework.data.repository.query.Param("id0") Long id0,
                             @org.springframework.data.repository.query.Param("order0") Integer order0,
                             @org.springframework.data.repository.query.Param("id1") Long id1,
                             @org.springframework.data.repository.query.Param("order1") Integer order1,
                             @org.springframework.data.repository.query.Param("id2") Long id2,
                             @org.springframework.data.repository.query.Param("order2") Integer order2,
                             @org.springframework.data.repository.query.Param("id3") Long id3,
                             @org.springframework.data.repository.query.Param("order3") Integer order3,
                             @org.springframework.data.repository.query.Param("id4") Long id4,
                             @org.springframework.data.repository.query.Param("order4") Integer order4,
                             @org.springframework.data.repository.query.Param("id5") Long id5,
                             @org.springframework.data.repository.query.Param("order5") Integer order5,
                             @org.springframework.data.repository.query.Param("id6") Long id6,
                             @org.springframework.data.repository.query.Param("order6") Integer order6,
                             @org.springframework.data.repository.query.Param("id7") Long id7,
                             @org.springframework.data.repository.query.Param("order7") Integer order7,
                             @org.springframework.data.repository.query.Param("id8") Long id8,
                             @org.springframework.data.repository.query.Param("order8") Integer order8,
                             @org.springframework.data.repository.query.Param("id9") Long id9,
                             @org.springframework.data.repository.query.Param("order9") Integer order9,
                             @org.springframework.data.repository.query.Param("id10") Long id10,
                             @org.springframework.data.repository.query.Param("order10") Integer order10,
                             @org.springframework.data.repository.query.Param("id11") Long id11,
                             @org.springframework.data.repository.query.Param("order11") Integer order11,
                             @org.springframework.data.repository.query.Param("id12") Long id12,
                             @org.springframework.data.repository.query.Param("order12") Integer order12,
                             @org.springframework.data.repository.query.Param("id13") Long id13,
                             @org.springframework.data.repository.query.Param("order13") Integer order13,
                             @org.springframework.data.repository.query.Param("id14") Long id14,
                             @org.springframework.data.repository.query.Param("order14") Integer order14,
                             @org.springframework.data.repository.query.Param("id15") Long id15,
                             @org.springframework.data.repository.query.Param("order15") Integer order15,
                             @org.springframework.data.repository.query.Param("id16") Long id16,
                             @org.springframework.data.repository.query.Param("order16") Integer order16,
                             @org.springframework.data.repository.query.Param("id17") Long id17,
                             @org.springframework.data.repository.query.Param("order17") Integer order17,
                             @org.springframework.data.repository.query.Param("id18") Long id18,
                             @org.springframework.data.repository.query.Param("order18") Integer order18,
                             @org.springframework.data.repository.query.Param("id19") Long id19,
                             @org.springframework.data.repository.query.Param("order19") Integer order19);

    // Enhanced stats
    @Query("SELECT FUNCTION('YEAR', a.startDate) as yr, COUNT(a), AVG(a.score) FROM Anime a WHERE a.startDate IS NOT NULL AND a.score IS NOT NULL AND a.score > 0 GROUP BY yr ORDER BY yr DESC")
    List<Object[]> getYearlyStats();

    @Query("SELECT ROUND(a.score) as bucket, COUNT(a) FROM Anime a WHERE a.score IS NOT NULL AND a.score > 0 GROUP BY bucket ORDER BY bucket")
    List<Object[]> getScoreDistribution();

    List<Anime> findByBroadcastDay(Integer broadcastDay, Sort sort);

    boolean existsByName(String name);

    boolean existsByBangumiId(Integer bangumiId);
}
