package com.otakulog.dto;

import com.otakulog.enums.InsightAspect;
import com.otakulog.enums.InsightQuoteField;
import com.otakulog.enums.MemoryContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TasteProfileDTO(long animeCount, long evidenceCount, long staleCount, List<Group> groups) {
    public record Group(InsightAspect aspect, String factor, long animeCount, List<Evidence> likes, List<Evidence> dislikes) {}
    public record Evidence(Long insightId, String insightKey, Long animeId, String animeName, Long memoryId,
                           String memoryKey, MemoryContext context, String scope, LocalDate watchedDate,
                           LocalDateTime sourceCreatedAt, Long sourceVersion, String statement,
                           InsightQuoteField quoteField, String quote) {}
}
