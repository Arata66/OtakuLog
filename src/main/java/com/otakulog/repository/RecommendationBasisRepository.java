package com.otakulog.repository;

import com.otakulog.entity.RecommendationBasis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RecommendationBasisRepository extends JpaRepository<RecommendationBasis,Long> {
    Optional<RecommendationBasis> findByBasisKey(String basisKey);
    Optional<RecommendationBasis> findByInsightKey(String insightKey);
}
