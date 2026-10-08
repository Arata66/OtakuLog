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

    List<EpisodeRecord> findByAnimeIdOrderByEpisodeNumberAsc(Long animeId);

    @Query("SELECT er.animeId, MAX(er.watchedDate) FROM EpisodeRecord er WHERE er.animeId IN :ids " +
            "AND er.source IN :sources AND er.watchedDate <= :today GROUP BY er.animeId")
    List<Object[]> findLatestKnownDates(@Param("ids") List<Long> ids, @Param("today") LocalDate today,
                                      @Param("sources") List<com.otakulog.enums.EpisodeRecordSource> sources);

    void deleteByAnimeIdAndEpisodeNumber(Long animeId, Integer episodeNumber);

    void deleteByAnimeId(Long animeId);

    boolean existsByAnimeId(Long animeId);

    @Query("SELECT er.watchedDate, COUNT(er) FROM EpisodeRecord er " +
           "WHERE er.watchedDate >= :from AND er.watchedDate <= :to " +
           "GROUP BY er.watchedDate")
    List<Object[]> countByWatchedDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
