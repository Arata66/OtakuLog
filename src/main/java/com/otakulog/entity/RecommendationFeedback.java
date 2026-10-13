package com.otakulog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.otakulog.dto.RecommendationDTO.Kind;

@Entity @Table(name="recommendation_feedback",indexes=@Index(name="idx_feedback_subject",columnList="subject_id,active")) @Getter @Setter
public class RecommendationFeedback extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=36) private String feedbackKey;
    @Column(nullable=false,length=100) private String owner;
    @Column(nullable=false,length=36) private String sourceBatchKey;
    @Column(nullable=false) private Integer subjectId;
    @Column(nullable=false,length=1000) private String name;
    @Enumerated(EnumType.STRING) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR) @Column(nullable=false,length=20) private Kind kind;
    @Column(nullable=false) private boolean active=true;
    @Column(nullable=false) private Long version=0L;
}
