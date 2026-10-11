package com.otakulog.repository;

import com.otakulog.entity.AiQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface AiQuotaRepository extends JpaRepository<AiQuota, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from AiQuota q where q.id = 1")
    Optional<AiQuota> findSingletonForUpdate();
}
