package com.otakulog.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.otakulog.enums.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

public final class TasteAiDTO {
    private TasteAiDTO() {}
    @JsonDeserialize(using = RequestDeserializer.class)
    public record Request(@JsonDeserialize(using = AnimeMemoryDTO.VersionDeserializer.class) Long sourceVersion,
                          @JsonDeserialize(using = SelectionsDeserializer.class) Map<String, String> selections,
                          @JsonDeserialize(using = MemoryInsightDTO.TextDeserializer.class) String requestKey) {}
    public static class RequestDeserializer extends JsonDeserializer<Request> {
        @Override public Request deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = parser.readValueAsTree();
            if (!root.isObject() || parser.nextToken() != null) throw JsonMappingException.from(parser, "请求必须为单个 JSON 对象");
            JsonNode version = root.path("sourceVersion"), key = root.path("requestKey");
            if (!version.isMissingNode() && !version.isNull() && (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0))
                throw JsonMappingException.from(parser, "来源版本必须为非负整数");
            if (!key.isMissingNode() && !key.isNull() && !key.isTextual()) throw JsonMappingException.from(parser, "请求键必须为字符串");
            JsonNode selections = root.path("selections");
            return new Request(version.isIntegralNumber() ? version.longValue() : null,
                    selections.isMissingNode() || selections.isNull() ? null : SelectionsDeserializer.parse(selections, parser),
                    key.isTextual() ? key.textValue() : null);
        }
    }
    public static class SelectionsDeserializer extends JsonDeserializer<Map<String, String>> {
        @Override public Map<String, String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parse(parser.readValueAsTree(), parser);
        }
        private static Map<String, String> parse(JsonNode value, JsonParser parser) throws IOException {
            if (!value.isObject()) throw JsonMappingException.from(parser, "选择必须为文字字段对象");
            Map<String, String> result = new LinkedHashMap<>();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!Set.of("content", "liked", "disliked").contains(field.getKey())
                        || (!field.getValue().isNull() && !field.getValue().isTextual()))
                    throw JsonMappingException.from(parser, "选择字段必须为原文字段字符串或空值");
                result.put(field.getKey(), field.getValue().isNull() ? null : field.getValue().textValue());
            }
            return result;
        }
    }
    public record Input(String memoryKey, Long sourceVersion, Map<String, String> selections, String scope,
                        MemoryContext context, String inputHash, int selectedCharacters) {}
    public record Usage(Integer promptTokens, Integer completionTokens, Integer cachedTokens, Integer reasoningTokens) {}
    public record Candidate(InsightQuoteField quoteField, String quote, String statement, InsightDirection direction,
                            InsightAspect aspect, String factor) {}
    public record Preview(boolean configured, String provider, String model, String promptVersion, Long sourceVersion,
                          Map<String, String> selections, String scope, MemoryContext context, String inputHash,
                          int selectedCharacters, int estimatedInputTokens, int maxOutputTokens, BigDecimal estimatedCostYuan,
                          boolean estimateOnly, long callsToday, int dailyLimit, long remainingCalls, String cachedRequestKey) {}
    public record Result(String requestKey, AiCallStatus status, boolean cached, List<MemoryInsightDTO.Entry> entries,
                         Usage usage, String message) {}
    public record UsageSummary(boolean configured, String model, java.time.LocalDate day, long callsToday,
                               int dailyLimit, long remainingCalls, long knownInputTokens, long knownOutputTokens, long unknownCallCount) {}
    public record ProviderResult(List<Candidate> candidates, Usage usage, AiErrorCategory errorCategory) {
        public boolean succeeded() { return errorCategory == null; }
    }
    public record Reservation(Long animeId, Long memoryId, String requestKey, Input input, Result existing) {}
}
