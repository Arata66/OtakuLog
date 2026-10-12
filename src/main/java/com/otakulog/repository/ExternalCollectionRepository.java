package com.otakulog.repository;

import com.otakulog.entity.ExternalCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ExternalCollectionRepository extends JpaRepository<ExternalCollection, Long> {
    Optional<ExternalCollection> findByCollectionKey(String key);
    Optional<ExternalCollection> findByUsernameAndSubjectId(String username, Integer subjectId);
    List<ExternalCollection> findByUsernameOrderBySubjectId(String username);
    @org.springframework.data.jpa.repository.Query("select distinct e.subjectId from ExternalCollection e where e.collectionType in (2,3,4,5)")
    List<Integer> findExcludedSubjectIds();
}
