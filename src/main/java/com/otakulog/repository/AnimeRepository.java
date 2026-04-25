package com.otakulog.repository;

import com.otakulog.entity.Anime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnimeRepository extends JpaRepository<Anime, Long> {
    // 按名称查询（模糊搜索）
    List<Anime> findByNameContaining(String name);
    
    // 按状态查询
    List<Anime> findByStatus(String status);
    
    // 按名称和状态同时查询
    List<Anime> findByNameContainingAndStatus(String name, String status);
}
