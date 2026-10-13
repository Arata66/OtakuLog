package com.otakulog.repository;
import com.otakulog.entity.RecommendationShown;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
public interface RecommendationShownRepository extends JpaRepository<RecommendationShown,Integer> {
    List<RecommendationShown> findByShownAtAfter(LocalDateTime cutoff);
    void deleteByShownAtBefore(LocalDateTime cutoff);
}
