package com.otakulog.entity;

import com.otakulog.enums.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_call", uniqueConstraints = @UniqueConstraint(name = "uk_ai_request_key", columnNames = "request_key"),
        indexes = {@Index(name = "idx_ai_created", columnList = "created_at"), @Index(name = "idx_ai_cache", columnList = "memory_key,source_version,input_hash")})
@AttributeOverrides({
    @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false)),
    @AttributeOverride(name = "updatedAt", column = @Column(name = "updated_at", nullable = false))
})
public class AiCall extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_key", nullable = false, length = 36)
    private String requestKey;
    @Column(name = "memory_key", nullable = false, length = 36)
    private String memoryKey;
    @Column(name = "source_version", nullable = false)
    private Long sourceVersion;
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;
    @Column(name = "model", nullable = false, length = 80)
    private String model;
    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;
    @Column(name = "selected_characters", nullable = false)
    private Integer selectedCharacters;
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private AiCallStatus status;
    @Column(name = "history_only", nullable = false)
    private boolean historyOnly;
    @Column(name = "error_category", nullable = true, length = 30)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private AiErrorCategory errorCategory;
    @Column(name = "prompt_tokens", nullable = true)
    private Integer promptTokens;
    @Column(name = "completion_tokens", nullable = true)
    private Integer completionTokens;
    @Column(name = "cached_tokens", nullable = true)
    private Integer cachedTokens;
    @Column(name = "reasoning_tokens", nullable = true)
    private Integer reasoningTokens;
    @Column(name = "completed_at", nullable = true)
    private LocalDateTime completedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey = value; }
    public String getMemoryKey() { return memoryKey; }
    public void setMemoryKey(String value) { memoryKey = value; }
    public Long getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Long value) { sourceVersion = value; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String value) { inputHash = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public Integer getSelectedCharacters() { return selectedCharacters; }
    public void setSelectedCharacters(Integer value) { selectedCharacters = value; }
    public AiCallStatus getStatus() { return status; }
    public void setStatus(AiCallStatus value) { status = value; }
    public boolean isHistoryOnly() { return historyOnly; }
    public void setHistoryOnly(boolean value) { historyOnly = value; }
    public AiErrorCategory getErrorCategory() { return errorCategory; }
    public void setErrorCategory(AiErrorCategory value) { errorCategory = value; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer value) { promptTokens = value; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer value) { completionTokens = value; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer value) { cachedTokens = value; }
    public Integer getReasoningTokens() { return reasoningTokens; }
    public void setReasoningTokens(Integer value) { reasoningTokens = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
