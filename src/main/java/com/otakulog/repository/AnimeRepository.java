package com.otakulog.repository;

import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnimeRepository extends JpaRepository<Anime, Long> {
    List<Anime> findByNameContaining(String name);

    List<Anime> findByStatus(AnimeStatus status);

    List<Anime> findByNameContainingAndStatus(String name, AnimeStatus status);

    long countByStatus(AnimeStatus status);
}
