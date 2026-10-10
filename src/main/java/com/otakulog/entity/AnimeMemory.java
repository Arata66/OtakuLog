package com.otakulog.entity;

import com.otakulog.enums.MemoryContext;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;

@Entity
@Table(name = "anime_memory", uniqueConstraints = @UniqueConstraint(name = "uk_am_memory_key", columnNames = "memory_key"),
        indexes = @Index(name = "idx_am_anime_created", columnList = "anime_id,created_at,id"))
@AttributeOverrides({
    @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false)),
    @AttributeOverride(name = "updatedAt", column = @Column(name = "updated_at", nullable = false))
})
public class AnimeMemory extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "anime_id", nullable = false)
    private Long animeId;
    @Column(name = "memory_key", nullable = false, length = 36)
    private String memoryKey;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(columnDefinition = "TEXT")
    private String liked;
    @Column(columnDefinition = "TEXT")
    private String disliked;
    @Column(length = 200)
    private String scope;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MemoryContext context = MemoryContext.NOTE;
    @Column(name = "watched_date")
    private LocalDate watchedDate;
    @Column(nullable = false)
    private Long version = 0L;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAnimeId() { return animeId; }
    public void setAnimeId(Long animeId) { this.animeId = animeId; }
    public String getMemoryKey() { return memoryKey; }
    public void setMemoryKey(String memoryKey) { this.memoryKey = memoryKey; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getLiked() { return liked; }
    public void setLiked(String liked) { this.liked = liked; }
    public String getDisliked() { return disliked; }
    public void setDisliked(String disliked) { this.disliked = disliked; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public MemoryContext getContext() { return context; }
    public void setContext(MemoryContext context) { this.context = context; }
    public LocalDate getWatchedDate() { return watchedDate; }
    public void setWatchedDate(LocalDate watchedDate) { this.watchedDate = watchedDate; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
