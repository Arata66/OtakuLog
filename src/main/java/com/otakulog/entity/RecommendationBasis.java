package com.otakulog.entity;

import com.otakulog.dto.RecommendationBasisDTO.Kind;
import com.otakulog.enums.InsightDirection;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="recommendation_basis") @Getter @Setter
public class RecommendationBasis extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=36) private String basisKey;
    @Column(nullable=false,unique=true,length=36) private String insightKey;
    @Column(nullable=false) private Long sourceVersion;
    @Column(nullable=false) private Long insightVersion;
    @Column(nullable=false,length=64) private String sourceFingerprint;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private InsightDirection direction;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private Kind kind;
    @Column(length=200) private String tag;
    private Integer subjectId;
    @Column(nullable=false) private boolean enabled=true;
    @Column(nullable=false) private Long version=0L;
}
