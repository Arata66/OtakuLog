package com.otakulog.service;

import com.otakulog.common.ConflictException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.MemoryInsightDTO;
import com.otakulog.dto.MemoryInsightDTO.Entry;
import com.otakulog.dto.MemoryInsightDTO.WriteRequest;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.entity.MemoryInsight;
import com.otakulog.enums.InsightOrigin;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.MemoryInsightRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.UUID;

@Service
public class MemoryInsightService {
    private final AnimeRepository anime;
    private final AnimeMemoryRepository memories;
    private final MemoryInsightRepository insights;
    @PersistenceContext private EntityManager entityManager;
    public MemoryInsightService(AnimeRepository anime, AnimeMemoryRepository memories, MemoryInsightRepository insights) {
        this.anime = anime; this.memories = memories; this.insights = insights;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemoryInsightDTO get(Long animeId, Long memoryId) {
        AnimeMemory memory = ownedMemory(animeId, memoryId);
        return new MemoryInsightDTO(memoryId, memory.getMemoryKey(), memory.getVersion(), insights.findByMemoryIdOrderByIdAsc(memoryId)
                .stream().map(value -> Entry.from(value, memory.getVersion())).toList());
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entry create(Long animeId, Long memoryId, WriteRequest request) {
        lockAnime(animeId);
        AnimeMemory memory = ownedMemory(animeId, memoryId);
        validateKey(request.key());
        MemoryInsight proposed = validateContent(memory, request);
        var existing = insights.findByInsightKey(request.key());
        if (existing.isPresent()) {
            MemoryInsight value = existing.get();
            if (!Objects.equals(value.getMemoryId(), memoryId) || value.getOrigin() != InsightOrigin.MANUAL || !sameContent(value, proposed))
                throw new ConflictException("观点提交键已被其他内容使用");
            return Entry.from(value, memory.getVersion());
        }
        if (insights.countByMemoryId(memoryId) >= 200) throw new IllegalArgumentException("每条记忆最多保留 200 条观点");
        proposed.setMemoryId(memoryId); proposed.setInsightKey(request.key()); proposed.setOrigin(InsightOrigin.MANUAL);
        return save(proposed, memory);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entry update(Long animeId, Long memoryId, Long insightId, WriteRequest request) {
        lockAnime(animeId);
        AnimeMemory memory = ownedMemory(animeId, memoryId);
        MemoryInsight value = ownedInsight(memoryId, insightId);
        MemoryInsight proposed = validateContent(memory, request);
        checkVersion(value, request.expectedVersion());
        if (request.key() != null && !request.key().equals(value.getInsightKey())) throw new IllegalArgumentException("观点提交键不能修改");
        if (sameContent(value, proposed)) return Entry.from(value, memory.getVersion());
        if (value.getVersion() == Long.MAX_VALUE) throw new ConflictException("观点版本已达到上限");
        copyContent(proposed, value); value.setVersion(value.getVersion() + 1);
        return save(value, memory);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long animeId, Long memoryId, Long insightId, Long expectedVersion) {
        lockAnime(animeId); ownedMemory(animeId, memoryId);
        MemoryInsight value = ownedInsight(memoryId, insightId);
        checkVersion(value, expectedVersion); insights.delete(value); insights.flush();
    }
    // 手工与模型候选共用原文校验，调用方必须先持有作品锁并核对当前来源。
    public MemoryInsight validateContent(AnimeMemory memory, WriteRequest request) {
        if (request.sourceVersion() == null || request.sourceVersion() < 0) throw new IllegalArgumentException("必须提供非负来源版本");
        if (!request.sourceVersion().equals(memory.getVersion())) throw new ConflictException("来源记忆已被修改，请核对原文");
        if (request.quoteField() == null || request.direction() == null || request.aspect() == null || request.status() == null)
            throw new IllegalArgumentException("请提供引用字段、方向、方面和状态");
        requiredText(request.quote(), 800, "引用"); requiredText(request.statement(), 500, "观点"); requiredText(request.factor(), 100, "因素");
        String source = switch (request.quoteField()) {
            case CONTENT -> memory.getContent(); case LIKED -> memory.getLiked(); case DISLIKED -> memory.getDisliked();
        };
        if (source == null || !source.contains(request.quote())) throw new IllegalArgumentException("引用必须是所选字段的原文子串");
        MemoryInsight value = new MemoryInsight();
        value.setSourceVersion(request.sourceVersion()); value.setQuoteField(request.quoteField()); value.setQuote(request.quote());
        value.setStatement(request.statement()); value.setDirection(request.direction()); value.setAspect(request.aspect());
        value.setFactor(request.factor()); value.setStatus(request.status());
        return value;
    }
    public void validateKey(String key) {
        try { if (key == null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("提交键必须为规范小写 UUID"); }
    }
    private void requiredText(String value, int limit, String name) {
        if (value == null || value.matches("(?U)[\\s\\p{javaWhitespace}]*") || value.length() > limit) throw new IllegalArgumentException(name + "必须为 1 至 " + limit + " 字符的非空文字");
    }
    private void lockAnime(Long id) { anime.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("番剧不存在")); }
    private AnimeMemory ownedMemory(Long animeId, Long memoryId) {
        return memories.findById(memoryId).filter(value -> Objects.equals(value.getAnimeId(), animeId))
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
    }
    private MemoryInsight ownedInsight(Long memoryId, Long insightId) {
        return insights.findById(insightId).filter(value -> Objects.equals(value.getMemoryId(), memoryId))
                .orElseThrow(() -> new ResourceNotFoundException("观点不存在"));
    }
    private void checkVersion(MemoryInsight value, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw new IllegalArgumentException("必须提供非负观点版本");
        if (!value.getVersion().equals(expectedVersion)) throw new ConflictException("观点已被修改，请刷新后核对");
    }
    private Entry save(MemoryInsight value, AnimeMemory memory) {
        insights.saveAndFlush(value); entityManager.refresh(value);
        return Entry.from(value, memory.getVersion());
    }
    private boolean sameContent(MemoryInsight a, MemoryInsight b) {
        return Objects.equals(a.getSourceVersion(), b.getSourceVersion()) && a.getQuoteField() == b.getQuoteField()
                && Objects.equals(a.getQuote(), b.getQuote()) && Objects.equals(a.getStatement(), b.getStatement())
                && a.getDirection() == b.getDirection() && a.getAspect() == b.getAspect()
                && Objects.equals(a.getFactor(), b.getFactor()) && a.getStatus() == b.getStatus();
    }
    private void copyContent(MemoryInsight from, MemoryInsight to) {
        to.setSourceVersion(from.getSourceVersion()); to.setQuoteField(from.getQuoteField()); to.setQuote(from.getQuote());
        to.setStatement(from.getStatement()); to.setDirection(from.getDirection()); to.setAspect(from.getAspect());
        to.setFactor(from.getFactor()); to.setStatus(from.getStatus());
    }
}
