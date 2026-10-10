package com.otakulog.repository;

import com.otakulog.entity.AnimeMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AnimeMemoryRepository extends JpaRepository<AnimeMemory, Long> {
    Optional<AnimeMemory> findByMemoryKey(String memoryKey);
    Page<AnimeMemory> findByAnimeId(Long animeId, Pageable pageable);
    long countByAnimeId(Long animeId);
    void deleteByAnimeId(Long animeId);
}
