package com.otakulog.repository;

import com.otakulog.entity.AiCall;
import com.otakulog.enums.AiCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AiCallRepository extends JpaRepository<AiCall, Long> {
    Optional<AiCall> findByRequestKey(String requestKey);
    Optional<AiCall> findFirstByMemoryKeyAndSourceVersionAndInputHashAndModelAndPromptVersionAndStatusOrderByIdAsc(
            String memoryKey, Long sourceVersion, String inputHash, String model, String promptVersion, AiCallStatus status);
    boolean existsByStatusAndHistoryOnlyFalseAndCreatedAtGreaterThanEqualAndCreatedAtLessThanEqual(AiCallStatus status, LocalDateTime since, LocalDateTime until);
    @Query("select count(c) from AiCall c where c.createdAt >= :start and c.createdAt < :end")
    long countCreatedInDay(LocalDateTime start, LocalDateTime end);
    @Query("select coalesce(sum(c.promptTokens),0), coalesce(sum(c.completionTokens),0), "
            + "sum(case when c.promptTokens is null or c.completionTokens is null then 1 else 0 end) from AiCall c")
    Object[] usageTotals();
}
