package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.common.*;
import com.otakulog.config.TasteAiProperties;
import com.otakulog.config.TasteAiRuntime;
import com.otakulog.dto.MemoryInsightDTO;
import com.otakulog.dto.TasteAiDTO.*;
import com.otakulog.entity.*;
import com.otakulog.enums.*;
import com.otakulog.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class TasteAiTransactions {
    private final AiCallRepository calls;
    private final AiQuotaRepository quotas;
    private final AnimeRepository anime;
    private final AnimeMemoryRepository memories;
    private final MemoryInsightRepository insights;
    private final MemoryInsightService validation;
    private final TasteAiProperties properties;
    private final TasteAiRuntime runtime;
    @PersistenceContext private EntityManager entityManager;
    public TasteAiTransactions(AiCallRepository calls, AiQuotaRepository quotas, AnimeRepository anime,
            AnimeMemoryRepository memories, MemoryInsightRepository insights, MemoryInsightService validation, TasteAiProperties properties, TasteAiRuntime runtime) {
        this.calls = calls; this.quotas = quotas; this.anime = anime; this.memories = memories;
        this.insights = insights; this.validation = validation; this.properties = properties; this.runtime = runtime;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Long animeId, Long memoryId, Request request) {
        var settings = runtime.snapshot();
        Input input = input(ownedMemory(animeId, memoryId), request);
        long today = callsToday(LocalDate.now());
        int estimated = (input.selectedCharacters() + DeepSeekInsightClient.SYSTEM_PROMPT.length()
                + Objects.toString(input.scope(), "").length() + input.context().name().length() + 200 + 1) / 2;
        BigDecimal cost = properties.getInputPricePerMillion().multiply(BigDecimal.valueOf(estimated))
                .add(properties.getOutputPricePerMillion().multiply(BigDecimal.valueOf(TasteAiProperties.MAX_OUTPUT_TOKENS)))
                .divide(BigDecimal.valueOf(1000000));
        return new Preview(settings.configured(), "DeepSeek", TasteAiProperties.MODEL, TasteAiProperties.PROMPT_VERSION,
                input.sourceVersion(), input.selections(), input.scope(), input.context(), input.inputHash(), input.selectedCharacters(),
                estimated, TasteAiProperties.MAX_OUTPUT_TOKENS, cost, true, today, settings.dailyLimit(),
                Math.max(0, settings.dailyLimit() - today), cache(input).map(AiCall::getRequestKey).orElse(null));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(Long animeId, Long memoryId, Request request) {
        var settings = runtime.snapshot();
        validation.validateKey(request.requestKey());
        AiQuota quota = lockQuota();
        anime.findByIdForUpdate(animeId).orElseThrow(() -> new ResourceNotFoundException("番剧不存在"));
        AnimeMemory memory = ownedMemory(animeId, memoryId); Input input = input(memory, request);
        Optional<AiCall> existing = calls.findByRequestKey(request.requestKey());
        if (existing.isPresent()) {
            AiCall call = existing.get();
            if (!sameInput(call, input)) throw new ConflictException("整理请求键已被其他来源或选择使用");
            return new Reservation(animeId, memoryId, call.getRequestKey(), null, result(call, false));
        }
        Optional<AiCall> cache = cache(input);
        if (cache.isPresent()) return new Reservation(animeId, memoryId, cache.get().getRequestKey(), null, result(cache.get(), true));
        if (!settings.configured()) throw new IllegalArgumentException("AI 整理尚未配置，仍可手工整理");
        if (insights.countByMemoryId(memoryId) > 194) throw new ConflictException("记忆观点剩余容量不足 6 条，请先整理已有观点");
        LocalDateTime now = LocalDateTime.now(); LocalDate today = now.toLocalDate();
        long ledger = dayLedgerCount(today);
        int reserved = today.equals(quota.getQuotaDate()) ? Math.max(quota.getReservedCalls(), (int) Math.min(Integer.MAX_VALUE, ledger))
                : (int) Math.min(Integer.MAX_VALUE, ledger);
        if (reserved >= settings.dailyLimit()) throw new ConflictException("今日 AI 整理次数已用完");
        if (calls.existsByStatusAndHistoryOnlyFalseAndCreatedAtGreaterThanEqualAndCreatedAtLessThanEqual(AiCallStatus.RUNNING, now.minusSeconds(120), now))
            throw new ConflictException("仍有整理请求运行中，请先核对请求状态，可能已计费");
        quota.setQuotaDate(today); quota.setReservedCalls(reserved + 1); quotas.save(quota);
        AiCall call = new AiCall(); call.setRequestKey(request.requestKey()); call.setMemoryKey(input.memoryKey());
        call.setSourceVersion(input.sourceVersion()); call.setInputHash(input.inputHash()); call.setModel(TasteAiProperties.MODEL);
        call.setPromptVersion(TasteAiProperties.PROMPT_VERSION); call.setSelectedCharacters(input.selectedCharacters()); call.setStatus(AiCallStatus.RUNNING);
        calls.saveAndFlush(call);
        return new Reservation(animeId, memoryId, call.getRequestKey(), input, null);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public Result finish(Reservation reservation, ProviderResult provider) {
        lockQuota();
        boolean animeExists = anime.findByIdForUpdate(reservation.animeId()).isPresent();
        AiCall call = calls.findByRequestKey(reservation.requestKey()).orElseThrow(() -> new ResourceNotFoundException("整理请求不存在"));
        if (call.getStatus() != AiCallStatus.RUNNING) return result(call, false);
        Usage usage = provider.usage();
        if (usage != null) { call.setPromptTokens(usage.promptTokens()); call.setCompletionTokens(usage.completionTokens());
            call.setCachedTokens(usage.cachedTokens()); call.setReasoningTokens(usage.reasoningTokens()); }
        AnimeMemory memory = animeExists ? memories.findById(reservation.memoryId()).orElse(null) : null;
        boolean current = memory != null && Objects.equals(memory.getAnimeId(), reservation.animeId())
                && Objects.equals(memory.getMemoryKey(), call.getMemoryKey()) && Objects.equals(memory.getVersion(), call.getSourceVersion());
        if (!current) { call.setStatus(AiCallStatus.STALE); call.setErrorCategory(AiErrorCategory.SOURCE_CHANGED);
        } else if (!provider.succeeded()) { call.setStatus(AiCallStatus.FAILED); call.setErrorCategory(provider.errorCategory());
        } else if (provider.candidates().size() > 6 || insights.countByMemoryId(memory.getId()) + provider.candidates().size() > 200) {
            call.setStatus(AiCallStatus.FAILED); call.setErrorCategory(AiErrorCategory.CAPACITY);
        } else {
            List<MemoryInsight> batch = new ArrayList<>();
            for (Candidate candidate : provider.candidates()) {
                MemoryInsight value = validation.validateContent(memory, new MemoryInsightDTO.WriteRequest(null, call.getSourceVersion(),
                        candidate.quoteField(), candidate.quote(), candidate.statement(), candidate.direction(), candidate.aspect(),
                        candidate.factor(), InsightStatus.PENDING, null));
                String selected = reservation.input().selections().get(candidate.quoteField().name().toLowerCase(Locale.ROOT));
                if (selected == null || !selected.contains(candidate.quote())) throw new IllegalArgumentException("模型引用不在本次选文中");
                value.setMemoryId(memory.getId()); value.setInsightKey(UUID.randomUUID().toString()); value.setOrigin(InsightOrigin.AI);
                value.setAiCallKey(call.getRequestKey()); value.setSuggestedQuoteField(candidate.quoteField()); value.setSuggestedQuote(candidate.quote());
                value.setSuggestedStatement(candidate.statement()); value.setSuggestedDirection(candidate.direction());
                value.setSuggestedAspect(candidate.aspect()); value.setSuggestedFactor(candidate.factor()); batch.add(value);
            }
            insights.saveAllAndFlush(batch); batch.forEach(entityManager::refresh); call.setStatus(AiCallStatus.SUCCEEDED);
        }
        call.setCompletedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        calls.saveAndFlush(call); entityManager.refresh(call); return result(call, false);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result get(String requestKey) {
        validation.validateKey(requestKey); return result(calls.findByRequestKey(requestKey)
                .orElseThrow(() -> new ResourceNotFoundException("整理请求不存在")), false);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageSummary usage() {
        var settings = runtime.snapshot();
        LocalDate day = LocalDate.now(); long today = callsToday(day); Object[] total = calls.usageTotals();
        if (total.length == 1 && total[0] instanceof Object[] nested) total = nested;
        return new UsageSummary(settings.configured(), TasteAiProperties.MODEL, day, today, settings.dailyLimit(),
                Math.max(0, settings.dailyLimit() - today), number(total[0]), number(total[1]), number(total[2]));
    }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private AiQuota lockQuota() { return quotas.findSingletonForUpdate().orElseThrow(() -> new ConflictException("AI 预算行缺失，请检查数据库迁移")); }
    private long dayLedgerCount(LocalDate day) { return calls.countCreatedInDay(day.atStartOfDay(), day.plusDays(1).atStartOfDay()); }
    private long callsToday(LocalDate day) {
        return Math.max(dayLedgerCount(day), quotas.findById(1L).filter(value -> day.equals(value.getQuotaDate())).map(AiQuota::getReservedCalls).orElse(0));
    }
    private Optional<AiCall> cache(Input input) { return calls.findFirstByMemoryKeyAndSourceVersionAndInputHashAndModelAndPromptVersionAndStatusOrderByIdAsc(
            input.memoryKey(), input.sourceVersion(), input.inputHash(), TasteAiProperties.MODEL, TasteAiProperties.PROMPT_VERSION, AiCallStatus.SUCCEEDED); }
    private boolean sameInput(AiCall call, Input input) {
        return call.getMemoryKey().equals(input.memoryKey()) && call.getSourceVersion().equals(input.sourceVersion())
                && call.getInputHash().equals(input.inputHash()) && call.getModel().equals(TasteAiProperties.MODEL)
                && call.getPromptVersion().equals(TasteAiProperties.PROMPT_VERSION);
    }
    private AnimeMemory ownedMemory(Long animeId, Long memoryId) { return memories.findById(memoryId)
            .filter(value -> Objects.equals(value.getAnimeId(), animeId)).orElseThrow(() -> new ResourceNotFoundException("记忆不存在")); }
    private Input input(AnimeMemory memory, Request request) {
        if (request.sourceVersion() == null || request.sourceVersion() < 0) throw new IllegalArgumentException("必须提供非负来源版本");
        if (!request.sourceVersion().equals(memory.getVersion())) throw new ConflictException("来源记忆已被修改，请核对原文");
        Map<String, String> selections = new LinkedHashMap<>(); int characters = 0;
        for (String field : List.of("content", "liked", "disliked")) {
            String source = switch (field) { case "content" -> memory.getContent(); case "liked" -> memory.getLiked(); default -> memory.getDisliked(); };
            String selected = request.selections() != null && request.selections().containsKey(field) ? request.selections().get(field) : source;
            if (selected != null && selected.isEmpty()) selected = null;
            if (selected != null) {
                if (selected.matches("(?U)[\\s\\p{javaWhitespace}]*") || source == null || !source.contains(selected))
                    throw new IllegalArgumentException("所选文字必须为对应原文的非空白连续子串");
                characters += selected.length();
            }
            selections.put(field, selected);
        }
        if (characters < 1 || characters > 6000) throw new IllegalArgumentException("请明确选择合计 1 至 6000 字符的原文");
        String hash;
        try {
            Map<String, Object> canonical = new LinkedHashMap<>(); canonical.put("memoryKey", memory.getMemoryKey());
            canonical.put("sourceVersion", memory.getVersion()); canonical.put("selections", selections); canonical.put("scope", memory.getScope());
            canonical.put("context", memory.getContext().name()); canonical.put("model", TasteAiProperties.MODEL); canonical.put("promptVersion", TasteAiProperties.PROMPT_VERSION);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("选文摘要计算失败"); }
        return new Input(memory.getMemoryKey(), memory.getVersion(), Collections.unmodifiableMap(selections), memory.getScope(), memory.getContext(), hash, characters);
    }
    private Result result(AiCall call, boolean cached) {
        AnimeMemory source = memories.findByMemoryKey(call.getMemoryKey()).orElse(null);
        List<MemoryInsightDTO.Entry> entries = call.getStatus() == AiCallStatus.SUCCEEDED ? insights.findByAiCallKeyOrderByIdAsc(call.getRequestKey()).stream()
                .map(value -> MemoryInsightDTO.Entry.from(value, source == null ? null : source.getVersion())).toList() : List.of();
        Usage usage = call.getPromptTokens() == null && call.getCompletionTokens() == null && call.getCachedTokens() == null && call.getReasoningTokens() == null
                ? null : new Usage(call.getPromptTokens(), call.getCompletionTokens(), call.getCachedTokens(), call.getReasoningTokens());
        LocalDateTime now = LocalDateTime.now();
        String message = switch (call.getStatus()) {
            case SUCCEEDED -> "整理完成，请核对候选观点";
            case FAILED -> "整理失败，请核对请求状态，可能已计费；不会自动重试";
            case STALE -> "来源已修改或删除，未保存候选，可能已计费";
            case RUNNING -> call.isHistoryOnly() || call.getCreatedAt().isBefore(now.minusSeconds(120)) || call.getCreatedAt().isAfter(now)
                    ? "整理结果未知，可能已计费；请按此请求键核对，不会自动重试" : "整理处理中，可能已计费；请按此请求键核对";
        };
        return new Result(call.getRequestKey(), call.getStatus(), cached, entries, usage, message);
    }
}
