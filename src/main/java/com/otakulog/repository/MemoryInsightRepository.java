package com.otakulog.repository;

import com.otakulog.entity.MemoryInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface MemoryInsightRepository extends JpaRepository<MemoryInsight, Long> {
    Optional<MemoryInsight> findByInsightKey(String insightKey);
    List<MemoryInsight> findByMemoryIdOrderByIdAsc(Long memoryId);
    long countByMemoryId(Long memoryId);
    @Modifying
    @Query("delete from MemoryInsight i where i.memoryId = :memoryId")
    void deleteByMemoryId(Long memoryId);
    @Modifying
    @Query("delete from MemoryInsight i where i.memoryId in (select m.id from AnimeMemory m where m.animeId = :animeId)")
    void deleteByAnimeId(Long animeId);
    @Query("select i, m, a.id, a.name from MemoryInsight i join AnimeMemory m on m.id = i.memoryId join Anime a on a.id = m.animeId order by i.id asc")
    List<Object[]> findProfileSources();
}
