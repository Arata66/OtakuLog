package com.otakulog.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.otakulog.entity.MemoryInsight;
import com.otakulog.enums.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

public record MemoryInsightDTO(Long memoryId, String memoryKey, Long sourceVersion, List<Entry> entries) {
    public record Entry(Long id, String key, Long memoryId, Long sourceVersion, InsightQuoteField quoteField,
                        String quote, String statement, InsightDirection direction, InsightAspect aspect, String factor,
                        InsightStatus status, InsightOrigin origin, String aiCallKey, InsightQuoteField suggestedQuoteField,
                        String suggestedQuote, String suggestedStatement,
                        InsightDirection suggestedDirection, InsightAspect suggestedAspect, String suggestedFactor,
                        Long version, LocalDateTime createdAt, LocalDateTime updatedAt, boolean stale) {
        public static Entry from(MemoryInsight value, Long currentSourceVersion) {
            return new Entry(value.getId(), value.getInsightKey(), value.getMemoryId(), value.getSourceVersion(),
                    value.getQuoteField(), value.getQuote(), value.getStatement(), value.getDirection(), value.getAspect(),
                    value.getFactor(), value.getStatus(), value.getOrigin(), value.getAiCallKey(), value.getSuggestedQuoteField(),
                    value.getSuggestedQuote(), value.getSuggestedStatement(),
                    value.getSuggestedDirection(), value.getSuggestedAspect(), value.getSuggestedFactor(), value.getVersion(),
                    value.getCreatedAt(), value.getUpdatedAt(), !value.getSourceVersion().equals(currentSourceVersion));
        }
    }
    public record WriteRequest(@JsonDeserialize(using = TextDeserializer.class) String key,
                               @JsonDeserialize(using = AnimeMemoryDTO.VersionDeserializer.class) Long sourceVersion,
                               @JsonDeserialize(using = QuoteFieldDeserializer.class) InsightQuoteField quoteField,
                               @JsonDeserialize(using = TextDeserializer.class) String quote,
                               @JsonDeserialize(using = TextDeserializer.class) String statement,
                               @JsonDeserialize(using = DirectionDeserializer.class) InsightDirection direction,
                               @JsonDeserialize(using = AspectDeserializer.class) InsightAspect aspect,
                               @JsonDeserialize(using = TextDeserializer.class) String factor,
                               @JsonDeserialize(using = StatusDeserializer.class) InsightStatus status,
                               @JsonDeserialize(using = AnimeMemoryDTO.VersionDeserializer.class) Long expectedVersion) {}

    public static class TextDeserializer extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser, "文字必须为字符串");
            return parser.getText();
        }
    }
    private abstract static class StrictEnumDeserializer<T extends Enum<T>> extends JsonDeserializer<T> {
        private final Class<T> type;
        protected StrictEnumDeserializer(Class<T> type) { this.type = type; }
        @Override public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser, "分类必须为枚举名称字符串");
            try { return Enum.valueOf(type, parser.getText()); }
            catch (IllegalArgumentException e) { throw context.weirdStringException(parser.getText(), type, "分类不在允许范围内"); }
        }
    }
    public static class QuoteFieldDeserializer extends StrictEnumDeserializer<InsightQuoteField> {
        public QuoteFieldDeserializer() { super(InsightQuoteField.class); }
    }
    public static class DirectionDeserializer extends StrictEnumDeserializer<InsightDirection> {
        public DirectionDeserializer() { super(InsightDirection.class); }
    }
    public static class AspectDeserializer extends StrictEnumDeserializer<InsightAspect> {
        public AspectDeserializer() { super(InsightAspect.class); }
    }
    public static class StatusDeserializer extends StrictEnumDeserializer<InsightStatus> {
        public StatusDeserializer() { super(InsightStatus.class); }
    }
}
