package com.otakulog.service;

import com.otakulog.common.ConflictException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeMemoryDTO;
import com.otakulog.dto.AnimeMemoryDTO.Entry;
import com.otakulog.dto.AnimeMemoryDTO.WriteRequest;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.enums.MemoryContext;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.AnimeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
public class AnimeMemoryService {
    private final AnimeRepository anime;
    private final AnimeMemoryRepository memories;
    @PersistenceContext
    private EntityManager entityManager;
    public AnimeMemoryService(AnimeRepository anime, AnimeMemoryRepository memories) {
        this.anime = anime; this.memories = memories;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnimeMemoryDTO get(Long animeId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("页码必须非负，每页 1–50 条");
        if (!anime.existsById(animeId)) throw new ResourceNotFoundException("番剧不存在");
        long total = memories.countByAnimeId(animeId);
        // 越界页直接返回空集，避免超大页码超过 JPA 偏移量范围。
        if ((long) page * size >= total) return new AnimeMemoryDTO(animeId, page, size, total, List.of());
        var result = memories.findByAnimeId(animeId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new AnimeMemoryDTO(animeId, page, size, result.getTotalElements(), result.getContent().stream().map(Entry::from).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entry create(Long animeId, WriteRequest request) {
        lockAnime(animeId);
        validateKey(request.key());
        AnimeMemory proposed = contentOf(request);
        var existing = memories.findByMemoryKey(request.key());
        if (existing.isPresent()) {
            AnimeMemory value = existing.get();
            if (!Objects.equals(value.getAnimeId(), animeId) || !sameContent(value, proposed))
                throw new ConflictException("这条记忆的提交键已被其他内容使用");
            return Entry.from(value);
        }
        proposed.setAnimeId(animeId); proposed.setMemoryKey(request.key());
        return saveEntry(proposed);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entry update(Long animeId, Long memoryId, WriteRequest request) {
        lockAnime(animeId);
        AnimeMemory value = ownedMemory(animeId, memoryId);
        checkVersion(value, request.expectedVersion());
        if (request.key() != null && !request.key().equals(value.getMemoryKey()))
            throw new IllegalArgumentException("记忆提交键不能修改");
        AnimeMemory proposed = contentOf(request);
        if (sameContent(value, proposed)) return Entry.from(value);
        copyContent(proposed, value);
        if (value.getVersion() == Long.MAX_VALUE) throw new ConflictException("记忆版本已达到上限");
        // 同一作品的写入共享悲观锁，版本仅在真实内容变化时递增。
        value.setVersion(value.getVersion() + 1);
        return saveEntry(value);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long animeId, Long memoryId, Long expectedVersion) {
        lockAnime(animeId);
        AnimeMemory value = ownedMemory(animeId, memoryId);
        checkVersion(value, expectedVersion);
        memories.delete(value);
        memories.flush();
    }

    private void lockAnime(Long animeId) {
        anime.findByIdForUpdate(animeId).orElseThrow(() -> new ResourceNotFoundException("番剧不存在"));
    }
    private Entry saveEntry(AnimeMemory value) {
        memories.saveAndFlush(value);
        // 审计时间以数据库精度为准，首次返回与重复提交保持一致。
        entityManager.refresh(value);
        return Entry.from(value);
    }
    private AnimeMemory ownedMemory(Long animeId, Long memoryId) {
        return memories.findById(memoryId).filter(value -> Objects.equals(value.getAnimeId(), animeId))
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
    }
    private void checkVersion(AnimeMemory value, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw new IllegalArgumentException("必须提供非负的记忆版本");
        if (!Objects.equals(value.getVersion(), expectedVersion)) throw new ConflictException("记忆已被修改，请刷新后核对再保存");
    }
    private void validateKey(String key) {
        try {
            if (key == null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("提交键必须为规范小写 UUID");
        }
    }
    private AnimeMemory contentOf(WriteRequest request) {
        if (request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("请填写想留下的感想");
        checkLength(request.content(), 16000, "感想");
        LocalDate date = request.watchedDate();
        if (date != null && (date.isBefore(LocalDate.of(1000, 1, 1)) || date.isAfter(LocalDate.now())))
            throw new IllegalArgumentException("观看日期必须在 1000 年至今天之间");
        AnimeMemory value = new AnimeMemory();
        value.setContent(request.content());
        value.setLiked(optionalText(request.liked(), 2000, "打动我的地方"));
        value.setDisliked(optionalText(request.disliked(), 2000, "不满意的地方"));
        value.setScope(optionalText(request.scope(), 200, "评价范围"));
        value.setContext(request.context() == null ? MemoryContext.NOTE : request.context());
        value.setWatchedDate(date);
        return value;
    }
    private String optionalText(String value, int limit, String name) {
        checkLength(value, limit, name);
        return value == null || value.isBlank() ? null : value;
    }
    private void checkLength(String value, int limit, String name) {
        if (value != null && value.length() > limit) throw new IllegalArgumentException(name + "不能超过 " + limit + " 字符");
    }
    private boolean sameContent(AnimeMemory a, AnimeMemory b) {
        return Objects.equals(a.getContent(), b.getContent()) && Objects.equals(a.getLiked(), b.getLiked())
                && Objects.equals(a.getDisliked(), b.getDisliked()) && Objects.equals(a.getScope(), b.getScope())
                && a.getContext() == b.getContext() && Objects.equals(a.getWatchedDate(), b.getWatchedDate());
    }
    private void copyContent(AnimeMemory from, AnimeMemory to) {
        to.setContent(from.getContent()); to.setLiked(from.getLiked()); to.setDisliked(from.getDisliked());
        to.setScope(from.getScope()); to.setContext(from.getContext()); to.setWatchedDate(from.getWatchedDate());
    }
}
