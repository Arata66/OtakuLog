package com.otakulog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name="recommendation_shown") @Getter @Setter
public class RecommendationShown {
    @Id private Integer subjectId;
    @Column(nullable=false) private LocalDateTime shownAt;
    @Column(nullable=false,length=36) private String batchKey;
}
