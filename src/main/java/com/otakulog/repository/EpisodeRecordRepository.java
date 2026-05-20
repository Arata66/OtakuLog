package com.otakulog.repository;

import com.otakulog.entity.EpisodeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EpisodeRecordRepository extends JpaRepository<EpisodeRecord, Long> {

    Optional<EpisodeRecord> findByAnimeIdAndEpisodeNumber(Long animeId, Integer episodeNumber);

    void deleteByAnimeIdAndEpisodeNumber(Long animeId, Integer episodeNumber);

    void deleteByAnimeId(Long animeId);

    boolean existsByAnimeId(Long animeId);

    @Query("SELECT er.watchedDate, COUNT(er) FROM EpisodeRecord er " +
           "WHERE er.watchedDate >= :from AND er.watchedDate <= :to " +
           "GROUP BY er.watchedDate")
    List<Object[]> countByWatchedDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
