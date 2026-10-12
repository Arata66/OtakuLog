package com.otakulog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Getter @Setter
@Table(name = "external_collection", uniqueConstraints = {
    @UniqueConstraint(name = "uk_ec_key", columnNames = "collection_key"),
    @UniqueConstraint(name = "uk_ec_identity", columnNames = {"username", "subject_id"})
})
public class ExternalCollection extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collection_key", nullable = false, length = 36) private String collectionKey;
    @Column(nullable = false, length = 100) private String username;
    @Column(name = "subject_id", nullable = false) private Integer subjectId;
    @Column(name = "collection_type") private Integer collectionType;
    @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(nullable = false) private Long version = 0L;
    @Column(name = "captured_at", nullable = false) private LocalDateTime capturedAt;
}
