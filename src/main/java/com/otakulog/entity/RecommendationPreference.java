package com.otakulog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="recommendation_preference") @Getter @Setter
public class RecommendationPreference extends BaseEntity {
    @Id private Long id;
    @Column(nullable=false,unique=true,length=36) private String preferenceKey;
    private Long version=0L;
    private Integer minYear;
    private Integer maxYear;
    @Column(nullable=false,columnDefinition="TEXT") private String excludedTagsJson;
    @Column(nullable=false,columnDefinition="TEXT") private String recallTagsJson;
    @Column(nullable=false) private boolean includeTracked;
}
