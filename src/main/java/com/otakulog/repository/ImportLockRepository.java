package com.otakulog.repository;

import com.otakulog.entity.ImportLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface ImportLockRepository extends JpaRepository<ImportLock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ImportLock l where l.id = 1")
    Optional<ImportLock> lockSingleton();
}
