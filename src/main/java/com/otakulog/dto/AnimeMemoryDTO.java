package com.otakulog.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.enums.MemoryContext;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

public record AnimeMemoryDTO(Long animeId, int page, int size, long totalEntries, List<Entry> entries) {
    public record Entry(Long id, String key, Long animeId, String content, String liked, String disliked,
                        String scope, MemoryContext context, LocalDate watchedDate, Long version,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static Entry from(AnimeMemory value) {
            return new Entry(value.getId(), value.getMemoryKey(), value.getAnimeId(), value.getContent(),
                    value.getLiked(), value.getDisliked(), value.getScope(), value.getContext(), value.getWatchedDate(),
                    value.getVersion(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
    public record WriteRequest(String key, String content, String liked, String disliked, String scope,
                               @JsonDeserialize(using = ContextDeserializer.class) MemoryContext context,
                               @JsonDeserialize(using = DateDeserializer.class) LocalDate watchedDate,
                               @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion) {}

    // 记忆接口与备份使用相同的文字格式，避免数字序号或日期数组被隐式转换。
    public static class ContextDeserializer extends JsonDeserializer<MemoryContext> {
        @Override
        public MemoryContext deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser, "语境必须为枚举名称字符串");
            try {
                return MemoryContext.valueOf(parser.getText());
            } catch (IllegalArgumentException e) {
                throw context.weirdStringException(parser.getText(), MemoryContext.class, "语境必须为 NOTE、INITIAL、REFLECTION 或 REWATCH");
            }
        }
    }

    public static class DateDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser, "观看日期必须为 ISO 日期字符串");
            try {
                return LocalDate.parse(parser.getText());
            } catch (DateTimeParseException e) {
                throw context.weirdStringException(parser.getText(), LocalDate.class, "观看日期必须为 yyyy-MM-dd");
            }
        }
    }

    public static class VersionDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw JsonMappingException.from(parser, "记忆版本必须为整数");
            return parser.getLongValue();
        }
    }
}
