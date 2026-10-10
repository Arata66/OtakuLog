package com.otakulog.entity;

import com.otakulog.enums.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "memory_insight", uniqueConstraints = @UniqueConstraint(name = "uk_mi_insight_key", columnNames = "insight_key"),
        indexes = @Index(name = "idx_mi_memory", columnList = "memory_id,id"))
@AttributeOverrides({
    @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false)),
    @AttributeOverride(name = "updatedAt", column = @Column(name = "updated_at", nullable = false))
})
public class MemoryInsight extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "insight_key", nullable = false, length = 36)
    private String insightKey;
    @Column(name = "memory_id", nullable = false)
    private Long memoryId;
    @Column(name = "source_version", nullable = false)
    private Long sourceVersion;
    @Column(name = "quote_field", nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightQuoteField quoteField;
    @Column(nullable = false, length = 800)
    private String quote;
    @Column(nullable = false, length = 500)
    private String statement;
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightDirection direction;
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightAspect aspect;
    @Column(nullable = false, length = 100)
    private String factor;
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightStatus status;
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightOrigin origin;
    @Column(name = "ai_call_key", length = 36)
    private String aiCallKey;
    @Column(name = "suggested_statement", length = 500)
    private String suggestedStatement;
    @Column(name = "suggested_quote_field", length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightQuoteField suggestedQuoteField;
    @Column(name = "suggested_quote", length = 800)
    private String suggestedQuote;
    @Column(name = "suggested_direction", length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightDirection suggestedDirection;
    @Column(name = "suggested_aspect", length = 20)
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private InsightAspect suggestedAspect;
    @Column(name = "suggested_factor", length = 100)
    private String suggestedFactor;
    @Column(nullable = false)
    private Long version = 0L;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInsightKey() { return insightKey; }
    public void setInsightKey(String insightKey) { this.insightKey = insightKey; }
    public Long getMemoryId() { return memoryId; }
    public void setMemoryId(Long memoryId) { this.memoryId = memoryId; }
    public Long getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Long sourceVersion) { this.sourceVersion = sourceVersion; }
    public InsightQuoteField getQuoteField() { return quoteField; }
    public void setQuoteField(InsightQuoteField quoteField) { this.quoteField = quoteField; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public InsightDirection getDirection() { return direction; }
    public void setDirection(InsightDirection direction) { this.direction = direction; }
    public InsightAspect getAspect() { return aspect; }
    public void setAspect(InsightAspect aspect) { this.aspect = aspect; }
    public String getFactor() { return factor; }
    public void setFactor(String factor) { this.factor = factor; }
    public InsightStatus getStatus() { return status; }
    public void setStatus(InsightStatus status) { this.status = status; }
    public InsightOrigin getOrigin() { return origin; }
    public void setOrigin(InsightOrigin origin) { this.origin = origin; }
    public String getAiCallKey() { return aiCallKey; }
    public void setAiCallKey(String aiCallKey) { this.aiCallKey = aiCallKey; }
    public String getSuggestedStatement() { return suggestedStatement; }
    public void setSuggestedStatement(String suggestedStatement) { this.suggestedStatement = suggestedStatement; }
    public InsightQuoteField getSuggestedQuoteField() { return suggestedQuoteField; }
    public void setSuggestedQuoteField(InsightQuoteField suggestedQuoteField) { this.suggestedQuoteField = suggestedQuoteField; }
    public String getSuggestedQuote() { return suggestedQuote; }
    public void setSuggestedQuote(String suggestedQuote) { this.suggestedQuote = suggestedQuote; }
    public InsightDirection getSuggestedDirection() { return suggestedDirection; }
    public void setSuggestedDirection(InsightDirection suggestedDirection) { this.suggestedDirection = suggestedDirection; }
    public InsightAspect getSuggestedAspect() { return suggestedAspect; }
    public void setSuggestedAspect(InsightAspect suggestedAspect) { this.suggestedAspect = suggestedAspect; }
    public String getSuggestedFactor() { return suggestedFactor; }
    public void setSuggestedFactor(String suggestedFactor) { this.suggestedFactor = suggestedFactor; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
