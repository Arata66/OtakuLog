package com.otakulog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(name = "import_batch", uniqueConstraints = @UniqueConstraint(name = "uk_ib_request", columnNames = "request_key"))
public class ImportBatch extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_key", nullable = false, length = 36) private String requestKey;
    @Column(nullable = false, length = 100) private String owner;
    @Column(nullable = false, length = 100) private String username;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT") private String resultJson;
}
