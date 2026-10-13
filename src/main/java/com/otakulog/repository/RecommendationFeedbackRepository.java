package com.otakulog.repository;
import com.otakulog.entity.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback,Long> {
    Optional<RecommendationFeedback> findByFeedbackKey(String key);
    boolean existsBySubjectIdAndActiveTrue(Integer subjectId);
}
