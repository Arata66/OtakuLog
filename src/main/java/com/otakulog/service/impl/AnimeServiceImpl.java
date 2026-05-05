package com.otakulog.service.impl;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.service.AnimeService;
import com.otakulog.util.SortUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnimeServiceImpl implements AnimeService {

    private final AnimeRepository animeRepository;

    public AnimeServiceImpl(AnimeRepository animeRepository) {
        this.animeRepository = animeRepository;
    }

    @Override
    public AnimeVO addAnime(AnimeDTO dto) {
        // 重复检测
        if (dto.getName() != null && animeRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("duplicate_name");
        }
        if (dto.getBangumiId() != null && animeRepository.existsByBangumiId(dto.getBangumiId())) {
            throw new IllegalArgumentException("duplicate_bangumi");
        }

        Anime anime = new Anime();
        anime.setName(dto.getName());
        anime.setTotalEpisodes(dto.getTotalEpisodes());
        anime.setSeason(dto.getSeason());
        anime.setScore(dto.getScore());
        anime.setRemark(dto.getRemark() != null ? dto.getRemark() : "");
        anime.setCoverUrl(dto.getCoverUrl());
        anime.setStartDate(parseDate(dto.getStartDate()));
        anime.setEndDate(parseDate(dto.getEndDate()));
        anime.setTags(dto.getTags());
        anime.setBroadcastDay(dto.getBroadcastDay());
        anime.setBangumiId(dto.getBangumiId());
        anime.setCurrentEpisode(1);
        anime.setStatus(AnimeStatus.WATCHING);
        anime.setLegacy(dto.getLegacy() != null && dto.getLegacy());
        anime.setWatchStartDate(parseDate(dto.getWatchStartDate()) != null ? parseDate(dto.getWatchStartDate()) : LocalDate.now());

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO nextEpisode(Long id) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

        if (anime.getCurrentEpisode() >= anime.getTotalEpisodes()) {
            throw new IllegalArgumentException("reached_max");
        }

        anime.setCurrentEpisode(anime.getCurrentEpisode() + 1);
        if (anime.getCurrentEpisode().equals(anime.getTotalEpisodes())) {
            anime.setStatus(AnimeStatus.FINISHED);
            anime.setEndDate(LocalDate.now());
        } else {
            anime.setStatus(AnimeStatus.WATCHING);
        }

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO prevEpisode(Long id) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

        if (anime.getCurrentEpisode() <= 1) {
            throw new IllegalArgumentException("reached_min");
        }

        anime.setCurrentEpisode(anime.getCurrentEpisode() - 1);
        anime.setStatus(AnimeStatus.WATCHING);
        anime.setEndDate(null);

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO updateAnime(Long id, AnimeUpdateDTO dto) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

        anime.setName(dto.getName());
        if (dto.getTotalEpisodes() != null) {
            anime.setTotalEpisodes(dto.getTotalEpisodes());
        }
        anime.setSeason(dto.getSeason());
        anime.setScore(dto.getScore());
        anime.setRemark(dto.getRemark() != null ? dto.getRemark() : "");
        anime.setCoverUrl(dto.getCoverUrl());
        anime.setStartDate(parseDate(dto.getStartDate()));
        anime.setEndDate(parseDate(dto.getEndDate()));
        anime.setTags(dto.getTags());
        anime.setBroadcastDay(dto.getBroadcastDay());
        if (dto.getBangumiId() != null) {
            anime.setBangumiId(dto.getBangumiId());
        }
        if (dto.getLegacy() != null) {
            anime.setLegacy(dto.getLegacy());
        }
        if (dto.getWatchStartDate() != null) {
            anime.setWatchStartDate(parseDate(dto.getWatchStartDate()));
        }

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO updateStatus(Long id, AnimeStatus status) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

        anime.setStatus(status);
        if (status == AnimeStatus.FINISHED) {
            anime.setEndDate(LocalDate.now());
        }
        return toVO(animeRepository.save(anime));
    }

    @Override
    public void deleteAnime(Long id) {
        if (!animeRepository.existsById(id)) {
            throw new IllegalArgumentException("未找到该番剧");
        }
        animeRepository.deleteById(id);
    }

    @Override
    public void batchDelete(List<Long> ids) {
        animeRepository.deleteAllById(ids);
    }

    @Override
    @Transactional
    public void batchUpdateStatus(List<Long> ids, AnimeStatus status) {
        if (ids == null || ids.isEmpty()) return;
        if (status == AnimeStatus.FINISHED) {
            animeRepository.batchFinishByIds(ids);
        } else {
            animeRepository.batchUpdateStatusByIds(ids, status);
        }
    }

    @Override
    public List<AnimeVO> searchAnime(String name, AnimeStatus status, String sortBy, String tag) {
        Sort sort = SortUtil.buildSort(sortBy);
        boolean hasTag = tag != null && !tag.trim().isEmpty();
        if (hasTag) {
            return animeRepository.findByTagContaining(tag.trim(), sort).stream().map(this::toVO).collect(Collectors.toList());
        }
        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasStatus = status != null;
        List<Anime> results;

        if (hasName && hasStatus) {
            results = animeRepository.findByNameContainingAndStatus(name, status, sort);
        } else if (hasName) {
            results = animeRepository.findByNameContaining(name, sort);
        } else if (hasStatus) {
            results = animeRepository.findByStatus(status, sort);
        } else {
            results = animeRepository.findAll(sort);
        }

        return results.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public Page<AnimeVO> searchAnimePaged(String name, AnimeStatus status, Pageable pageable, String tag) {
        boolean hasTag = tag != null && !tag.trim().isEmpty();
        if (hasTag) {
            Page<Anime> page = animeRepository.findByTagContaining(tag.trim(), pageable);
            List<AnimeVO> voList = page.getContent().stream().map(this::toVO).collect(Collectors.toList());
            return new PageImpl<>(voList, pageable, page.getTotalElements());
        }
        Page<Anime> page;
        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasStatus = status != null;

        if (hasName && hasStatus) {
            page = animeRepository.findByNameContainingAndStatus(name, status, pageable);
        } else if (hasName) {
            page = animeRepository.findByNameContaining(name, pageable);
        } else if (hasStatus) {
            page = animeRepository.findByStatus(status, pageable);
        } else {
            page = animeRepository.findAll(pageable);
        }

        List<AnimeVO> voList = page.getContent().stream().map(this::toVO).collect(Collectors.toList());
        return new PageImpl<>(voList, pageable, page.getTotalElements());
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", animeRepository.count());
        stats.put("watching", animeRepository.countByStatus(AnimeStatus.WATCHING));
        stats.put("finished", animeRepository.countByStatus(AnimeStatus.FINISHED));
        stats.put("planning", animeRepository.countByStatus(AnimeStatus.PLANNING));
        stats.put("dropped", animeRepository.countByStatus(AnimeStatus.DROPPED));
        return stats;
    }

    @Override
    public Map<String, Object> getDetailedStats() {
        Map<String, Object> stats = new HashMap<>();

        List<Object[]> rows = animeRepository.getAggregatedStats();
        Object[] row = (rows != null && !rows.isEmpty()) ? rows.get(0) : new Object[11];
        long total = toLong(row[0]);
        long watching = toLong(row[1]);
        long finished = toLong(row[2]);
        long planning = toLong(row[3]);
        long dropped = toLong(row[4]);
        long totalEpisodes = toLong(row[5]);
        long watchedEpisodes = toLong(row[6]);
        double avgScore = toDouble(row[7]);
        long highScore = toLong(row[8]);
        long mediumScore = toLong(row[9]);
        long lowScore = toLong(row[10]);

        stats.put("total", total);
        stats.put("watching", watching);
        stats.put("finished", finished);
        stats.put("planning", planning);
        stats.put("dropped", dropped);

        double progressPercentage = totalEpisodes > 0 ? (watchedEpisodes * 100.0 / totalEpisodes) : 0;
        stats.put("totalEpisodes", totalEpisodes);
        stats.put("watchedEpisodes", watchedEpisodes);
        stats.put("progressPercentage", Math.round(progressPercentage * 10.0) / 10.0);

        stats.put("averageScore", Math.round(avgScore * 10.0) / 10.0);
        stats.put("highScore", highScore);
        stats.put("mediumScore", mediumScore);
        stats.put("lowScore", lowScore);

        return stats;
    }

    @Override
    public List<AnimeVO> findAll() {
        return animeRepository.findAll().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getSeasonStats() {
        List<Object[]> rows = animeRepository.getSeasonStats();
        List<Map<String, Object>> seasons = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("season", row[0]);
            entry.put("count", row[1]);
            entry.put("avgScore", row[2] != null ? Math.round(((Double) row[2]) * 10.0) / 10.0 : null);
            seasons.add(entry);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("seasons", seasons);
        return result;
    }

    @Override
    public List<AnimeVO> getTimeline(String mode) {
        String sortField = "air".equals(mode) ? "startDate" : "watchStartDate";
        return animeRepository.findAll(Sort.by(Sort.Direction.DESC, sortField)).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public String exportJson() {
        try {
            List<Anime> all = animeRepository.findAll();
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> exportList = new ArrayList<>();
            for (Anime a : all) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", a.getName());
                map.put("totalEpisodes", a.getTotalEpisodes());
                map.put("currentEpisode", a.getCurrentEpisode());
                map.put("status", a.getStatus().name().toLowerCase());
                map.put("score", a.getScore());
                map.put("season", a.getSeason());
                map.put("remark", a.getRemark());
                map.put("coverUrl", a.getCoverUrl());
                map.put("startDate", a.getStartDate() != null ? a.getStartDate().toString() : null);
                map.put("endDate", a.getEndDate() != null ? a.getEndDate().toString() : null);
                map.put("tags", a.getTags());
                map.put("broadcastDay", a.getBroadcastDay());
                map.put("bangumiId", a.getBangumiId());
                map.put("sortOrder", a.getSortOrder());
                map.put("watchStartDate", a.getWatchStartDate() != null ? a.getWatchStartDate().toString() : null);
                map.put("legacy", a.isLegacy());
                exportList.add(map);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportList);
        } catch (Exception e) {
            throw new RuntimeException("导出失败", e);
        }
    }

    @Override
    public Map<String, Object> importJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<>() {});

            Map<String, Anime> existing = animeRepository.findAll().stream()
                    .collect(Collectors.toMap(Anime::getName, a -> a, (a, b) -> a));

            int created = 0, updated = 0;
            List<AnimeVO> result = new ArrayList<>();
            for (Map<String, Object> map : list) {
                String name = (String) map.get("name");
                Anime anime = existing.getOrDefault(name, new Anime());

                boolean isNew = anime.getId() == null;
                anime.setName(name);
                anime.setTotalEpisodes(toInt(map.get("totalEpisodes")));
                anime.setCurrentEpisode(toInt(map.get("currentEpisode")));
                anime.setScore(toDouble(map.get("score")));
                anime.setSeason((String) map.get("season"));
                anime.setRemark((String) map.getOrDefault("remark", ""));
                anime.setCoverUrl((String) map.get("coverUrl"));
                anime.setStartDate(parseDate((String) map.get("startDate")));
                anime.setEndDate(parseDate((String) map.get("endDate")));
                String status = (String) map.getOrDefault("status", "watching");
                anime.setStatus(AnimeStatus.valueOf(status.toUpperCase()));
                anime.setTags((String) map.get("tags"));
                anime.setBroadcastDay(toIntOrNull(map.get("broadcastDay")));
                anime.setBangumiId(toIntOrNull(map.get("bangumiId")));
                anime.setSortOrder(toIntOrNull(map.get("sortOrder")));
                anime.setWatchStartDate(parseDate((String) map.get("watchStartDate")));
                Object legacyObj = map.get("legacy");
                anime.setLegacy(legacyObj != null && Boolean.TRUE.equals(legacyObj));

                result.add(toVO(animeRepository.save(anime)));
                if (isNew) created++; else updated++;
            }

            Map<String, Object> res = new HashMap<>();
            res.put("created", created);
            res.put("updated", updated);
            res.put("list", result);
            return res;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的状态值: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("导入失败: JSON 格式错误", e);
        }
    }

    @Override
    public Map<String, Object> getEnhancedStats() {
        Map<String, Object> stats = new HashMap<>();

        // Yearly stats
        List<Object[]> yearlyRows = animeRepository.getYearlyStats();
        List<Map<String, Object>> yearly = new ArrayList<>();
        for (Object[] row : yearlyRows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("year", row[0]);
            entry.put("count", row[1]);
            entry.put("avgScore", row[2] != null ? Math.round(((Double) row[2]) * 10.0) / 10.0 : null);
            yearly.add(entry);
        }
        stats.put("yearly", yearly);

        // Score distribution
        List<Object[]> scoreRows = animeRepository.getScoreDistribution();
        List<Map<String, Object>> scoreDist = new ArrayList<>();
        for (Object[] row : scoreRows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("bucket", row[0]);
            entry.put("count", row[1]);
            scoreDist.add(entry);
        }
        stats.put("scoreDistribution", scoreDist);

        // Tag breakdown
        List<Anime> all = animeRepository.findAll();
        Map<String, Integer> tagCounts = new HashMap<>();
        for (Anime a : all) {
            if (a.getTags() != null && !a.getTags().trim().isEmpty()) {
                for (String tag : a.getTags().split(",")) {
                    String t = tag.trim();
                    if (!t.isEmpty()) tagCounts.merge(t, 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> tagList = new ArrayList<>();
        tagCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
                .forEach(e -> { Map<String, Object> m = new HashMap<>(); m.put("tag", e.getKey()); m.put("count", e.getValue()); tagList.add(m); });
        stats.put("tags", tagList);

        // Watching habits — 只统计非旧番，用 watchStartDate
        long totalWatched = 0;
        long totalDays = 0;
        int counted = 0;
        int legacyCount = 0;
        for (Anime a : all) {
            if (a.isLegacy()) { legacyCount++; continue; }
            LocalDate watchStart = a.getWatchStartDate() != null ? a.getWatchStartDate() : (a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : null);
            if (watchStart != null && a.getCurrentEpisode() != null && a.getCurrentEpisode() > 0) {
                LocalDate end = a.getEndDate() != null ? a.getEndDate() : LocalDate.now();
                long days = java.time.temporal.ChronoUnit.DAYS.between(watchStart, end);
                if (days > 0) {
                    totalWatched += a.getCurrentEpisode();
                    totalDays += days;
                    counted++;
                }
            }
        }
        double epPerDay = totalDays > 0 ? Math.round((double) totalWatched / totalDays * 100.0) / 100.0 : 0;
        double epPerMonth = totalDays > 0 ? Math.round((double) totalWatched / totalDays * 30 * 10.0) / 10.0 : 0;
        stats.put("episodesPerDay", epPerDay);
        stats.put("episodesPerMonth", epPerMonth);
        stats.put("animeCountedForHabits", counted);
        stats.put("legacyCount", legacyCount);

        return stats;
    }

    @Override
    public Map<Integer, List<AnimeVO>> getCalendarData() {
        Map<Integer, List<AnimeVO>> calendar = new LinkedHashMap<>();
        Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "name"));
        for (int day = 1; day <= 7; day++) {
            List<AnimeVO> list = animeRepository.findByBroadcastDay(day, sort)
                    .stream().map(this::toVO).collect(Collectors.toList());
            calendar.put(day, list);
        }
        return calendar;
    }

    @Override
    @Transactional
    public void reorderAnime(List<Map<String, Object>> orders) {
        if (orders == null || orders.isEmpty()) return;
        // 分批处理，每批最多 20 条
        for (int i = 0; i < orders.size(); i += 20) {
            List<Map<String, Object>> batch = orders.subList(i, Math.min(i + 20, orders.size()));
            List<Long> ids = new ArrayList<>();
            Long[] idArr = new Long[20];
            Integer[] orderArr = new Integer[20];
            for (int j = 0; j < 20; j++) {
                if (j < batch.size()) {
                    Long id = Long.valueOf(batch.get(j).get("id").toString());
                    Integer order = Integer.valueOf(batch.get(j).get("sortOrder").toString());
                    ids.add(id);
                    idArr[j] = id;
                    orderArr[j] = order;
                } else {
                    // 填充占位值（不会匹配任何记录）
                    idArr[j] = -1L;
                    orderArr[j] = 0;
                }
            }
            animeRepository.batchUpdateSortOrder(ids,
                    idArr[0], orderArr[0], idArr[1], orderArr[1], idArr[2], orderArr[2],
                    idArr[3], orderArr[3], idArr[4], orderArr[4], idArr[5], orderArr[5],
                    idArr[6], orderArr[6], idArr[7], orderArr[7], idArr[8], orderArr[8],
                    idArr[9], orderArr[9], idArr[10], orderArr[10], idArr[11], orderArr[11],
                    idArr[12], orderArr[12], idArr[13], orderArr[13], idArr[14], orderArr[14],
                    idArr[15], orderArr[15], idArr[16], orderArr[16], idArr[17], orderArr[17],
                    idArr[18], orderArr[18], idArr[19], orderArr[19]);
        }
    }

    private AnimeVO toVO(Anime anime) {
        AnimeVO vo = new AnimeVO();
        vo.setId(anime.getId());
        vo.setName(anime.getName());
        vo.setCurrentEpisode(anime.getCurrentEpisode());
        vo.setTotalEpisodes(anime.getTotalEpisodes());
        vo.setStatus(anime.getStatus().name().toLowerCase());
        vo.setStatusDisplay(anime.getStatus().getDisplayName());
        vo.setScore(anime.getScore());
        vo.setSeason(anime.getSeason());
        vo.setRemark(anime.getRemark());
        vo.setCoverUrl(anime.getCoverUrl());
        vo.setStartDate(anime.getStartDate() != null ? anime.getStartDate().toString() : null);
        vo.setEndDate(anime.getEndDate() != null ? anime.getEndDate().toString() : null);
        vo.setTags(anime.getTags());
        vo.setSortOrder(anime.getSortOrder());
        vo.setBroadcastDay(anime.getBroadcastDay());
        vo.setBangumiId(anime.getBangumiId());
        vo.setWatchStartDate(anime.getWatchStartDate() != null ? anime.getWatchStartDate().toString() : null);
        vo.setLegacy(anime.isLegacy());
        if (anime.getTotalEpisodes() != null && anime.getTotalEpisodes() > 0) {
            vo.setProgress(Math.round((double) anime.getCurrentEpisode() / anime.getTotalEpisodes() * 1000.0) / 10.0);
        }
        return vo;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        return Integer.parseInt(obj.toString());
    }

    private Integer toIntOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return null; }
    }

    private long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Long l) return l;
        if (obj instanceof Number n) return n.longValue();
        return Long.parseLong(obj.toString());
    }

    private Double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Double d) return d;
        if (obj instanceof Number n) return n.doubleValue();
        return Double.parseDouble(obj.toString());
    }
}
