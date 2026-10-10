package com.otakulog.service;

import com.otakulog.dto.TasteProfileDTO;
import com.otakulog.dto.TasteProfileDTO.Evidence;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.entity.MemoryInsight;
import com.otakulog.enums.InsightAspect;
import com.otakulog.enums.InsightDirection;
import com.otakulog.enums.InsightStatus;
import com.otakulog.repository.MemoryInsightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

@Service
public class TasteProfileService {
    private final MemoryInsightRepository insights;
    public TasteProfileService(MemoryInsightRepository insights) { this.insights = insights; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TasteProfileDTO get() {
        var groups = new TreeMap<GroupKey, GroupBuilder>();
        Set<Long> animeIds = new HashSet<>();
        long evidenceCount = 0, staleCount = 0;
        // 只投影作品定位字段，避免标签抓取造成逐作品查询。
        for (Object[] row : insights.findProfileSources()) {
            MemoryInsight insight = (MemoryInsight) row[0];
            AnimeMemory memory = (AnimeMemory) row[1]; Long animeId = (Long) row[2]; String animeName = (String) row[3];
            if (!insight.getSourceVersion().equals(memory.getVersion())) { staleCount++; continue; }
            if (insight.getStatus() != InsightStatus.CONFIRMED) continue;
            var key = new GroupKey(insight.getAspect(), normalizeFactor(insight.getFactor()));
            var group = groups.computeIfAbsent(key, ignored -> new GroupBuilder(insight.getAspect(), insight.getFactor()));
            var evidence = new Evidence(insight.getId(), insight.getInsightKey(), animeId, animeName, memory.getId(),
                    memory.getMemoryKey(), memory.getContext(), memory.getScope(), memory.getWatchedDate(), memory.getCreatedAt(),
                    insight.getSourceVersion(), insight.getStatement(), insight.getQuoteField(), insight.getQuote());
            if (insight.getDirection() == InsightDirection.LIKE) group.likes.add(evidence); else group.dislikes.add(evidence);
            group.animeIds.add(animeId); animeIds.add(animeId); evidenceCount++;
        }
        return new TasteProfileDTO(animeIds.size(), evidenceCount, staleCount,
                groups.values().stream().map(GroupBuilder::build).toList());
    }
    private String normalizeFactor(String factor) {
        return Normalizer.normalize(factor, Normalizer.Form.NFKC).replaceAll("(?U)[\\s\\p{javaWhitespace}]+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private record GroupKey(InsightAspect aspect, String factor) implements Comparable<GroupKey> {
        @Override public int compareTo(GroupKey other) {
            int order = aspect.compareTo(other.aspect); return order == 0 ? factor.compareTo(other.factor) : order;
        }
    }
    private static class GroupBuilder {
        final InsightAspect aspect; final String factor;
        final Set<Long> animeIds = new HashSet<>();
        final List<Evidence> likes = new ArrayList<>(), dislikes = new ArrayList<>();
        GroupBuilder(InsightAspect aspect, String factor) { this.aspect = aspect; this.factor = factor; }
        TasteProfileDTO.Group build() { return new TasteProfileDTO.Group(aspect, factor, animeIds.size(), List.copyOf(likes), List.copyOf(dislikes)); }
    }
}
