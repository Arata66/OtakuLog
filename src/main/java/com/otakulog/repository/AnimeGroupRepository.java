package com.otakulog.repository;

import com.otakulog.entity.AnimeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnimeGroupRepository extends JpaRepository<AnimeGroup, Long> {

    List<AnimeGroup> findAllByOrderBySortOrderAsc();

    @Modifying
    @Query(value = "INSERT INTO anime_group_relation (group_id, anime_id) VALUES (:groupId, :animeId)", nativeQuery = true)
    void addAnimeToGroup(@Param("groupId") Long groupId, @Param("animeId") Long animeId);

    @Modifying
    @Query(value = "DELETE FROM anime_group_relation WHERE group_id = :groupId AND anime_id = :animeId", nativeQuery = true)
    void removeAnimeFromGroup(@Param("groupId") Long groupId, @Param("animeId") Long animeId);

    @Query(value = "SELECT anime_id FROM anime_group_relation WHERE group_id = :groupId", nativeQuery = true)
    List<Long> findAnimeIdsByGroupId(@Param("groupId") Long groupId);

    @Query(value = "SELECT group_id FROM anime_group_relation WHERE anime_id = :animeId", nativeQuery = true)
    List<Long> findGroupIdsByAnimeId(@Param("animeId") Long animeId);

    @Query(value = "SELECT COUNT(*) FROM anime_group_relation WHERE group_id = :groupId", nativeQuery = true)
    long countAnimeByGroupId(@Param("groupId") Long groupId);
}
