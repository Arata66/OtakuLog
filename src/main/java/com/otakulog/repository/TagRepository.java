package com.otakulog.repository;

import com.otakulog.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    @Query("SELECT t FROM Tag t JOIN t.animes a WHERE a.id = :animeId")
    List<Tag> findByAnimeId(@org.springframework.data.repository.query.Param("animeId") Long animeId);

    @Query("SELECT t.name, COUNT(a.id) FROM Tag t JOIN t.animes a GROUP BY t.id, t.name ORDER BY COUNT(a.id) DESC")
    List<Object[]> findAllWithCount();
}
