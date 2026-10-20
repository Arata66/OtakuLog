package com.otakulog.service;

import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeMemoryDTO;
import com.otakulog.dto.MemoryReviewDTO;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.enums.MemoryContext;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.AnimeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemoryReviewService {
    private static final int SIZE = 20;
    private final AnimeRepository anime;
    private final AnimeMemoryRepository memories;

    public MemoryReviewService(AnimeRepository anime, AnimeMemoryRepository memories) {
        this.anime = anime; this.memories = memories;
    }

    public MemoryReviewDTO get(int page, Long animeId, MemoryContext context, String month, String order) {
        if (page < 0 || (animeId != null && animeId < 1)) throw new IllegalArgumentException("页码必须非负，作品编号必须为正数");
        if (!List.of("newest", "oldest").contains(order)) throw new IllegalArgumentException("排序必须为 newest 或 oldest");
        YearMonth period = parseMonth(month);
        Specification<AnimeMemory> filter = (root, query, builder) -> {
            var conditions = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (animeId != null) conditions.add(builder.equal(root.get("animeId"), animeId));
            if (context != null) conditions.add(builder.equal(root.get("context"), context));
            if (period != null) {
                conditions.add(builder.greaterThanOrEqualTo(root.get("createdAt"), period.atDay(1).atStartOfDay()));
                // 末年无需构造超出数据库日期范围的下一年。
                if (!period.equals(YearMonth.of(9999, 12)))
                    conditions.add(builder.lessThan(root.get("createdAt"), period.plusMonths(1).atDay(1).atStartOfDay()));
            }
            return builder.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var works = anime.findMemoryReviewWorks().stream().map(work -> new MemoryReviewDTO.Work(work.getId(), work.getName())).toList();
        long total = memories.count(filter);
        if ((long) page * SIZE >= total) return new MemoryReviewDTO(page, SIZE, total, List.of(), works);
        var direction = "oldest".equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var rows = memories.findAll(filter, PageRequest.of(page, SIZE, Sort.by(direction, "createdAt", "id"))).getContent();
        var names = anime.findAllById(rows.stream().map(AnimeMemory::getAnimeId).distinct().toList()).stream()
                .collect(Collectors.toMap(com.otakulog.entity.Anime::getId, Function.identity()));
        var entries = rows.stream().map(memory -> new MemoryReviewDTO.Item(names.get(memory.getAnimeId()).getName(),
                AnimeMemoryDTO.Entry.from(memory))).toList();
        return new MemoryReviewDTO(page, SIZE, total, entries, works);
    }

    public MemoryReviewDTO.Location locate(Long memoryId) {
        var memory = memories.findById(memoryId).orElseThrow(() -> new ResourceNotFoundException("这条感想已不存在，请刷新回顾页"));
        var work = anime.findById(memory.getAnimeId()).orElseThrow(() -> new ResourceNotFoundException("作品已不存在"));
        long newer = memories.countNewer(memory.getAnimeId(), memory.getCreatedAt(), memory.getId());
        return new MemoryReviewDTO.Location(com.otakulog.util.AnimeVOMapper.toVO(work), memoryId, Math.toIntExact(newer / SIZE));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            if (!month.matches("[1-9][0-9]{3}-[0-9]{2}")) throw new IllegalArgumentException();
            return YearMonth.parse(month);
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new IllegalArgumentException("书写月份必须为 1000—9999 年的合法 yyyy-MM 月份");
        }
    }
}
