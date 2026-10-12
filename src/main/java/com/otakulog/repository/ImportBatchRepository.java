package com.otakulog.repository;

import com.otakulog.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    Optional<ImportBatch> findByRequestKey(String key);
}
