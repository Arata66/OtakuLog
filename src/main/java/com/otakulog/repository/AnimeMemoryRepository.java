package com.otakulog.repository;

import com.otakulog.entity.AnimeMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AnimeMemoryRepository extends JpaRepository<AnimeMemory, Long>, JpaSpecificationExecutor<AnimeMemory> {
    Optional<AnimeMemory> findByMemoryKey(String memoryKey);
    Page<AnimeMemory> findByAnimeId(Long animeId, Pageable pageable);
    long countByAnimeId(Long animeId);
    void deleteByAnimeId(Long animeId);

    @Query("select count(m) from AnimeMemory m where m.animeId = :animeId and "
            + "(m.createdAt > :createdAt or (m.createdAt = :createdAt and m.id > :id))")
    long countNewer(@Param("animeId") Long animeId, @Param("createdAt") LocalDateTime createdAt, @Param("id") Long id);
}
