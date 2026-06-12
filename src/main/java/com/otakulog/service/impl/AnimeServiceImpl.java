package com.otakulog.service.impl;

import com.otakulog.common.ExternalApiException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.dto.BangumiResult;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BangumiService;
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
    private final BangumiService bangumiService;
    private final EpisodeRecordRepository episodeRecordRepository;

    public AnimeServiceImpl(AnimeRepository animeRepository, BangumiService bangumiService,
                            EpisodeRecordRepository episodeRecordRepository) {
        this.animeRepository = animeRepository;
        this.bangumiService = bangumiService;
        this.episodeRecordRepository = episodeRecordRepository;
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
        // 支持从表单选择状态，默认追中
        AnimeStatus targetStatus;
        if (dto.getStatus() != null && !dto.getStatus().trim().isEmpty()) {
            targetStatus = AnimeStatus.valueOf(dto.getStatus().toUpperCase());
        } else {
            targetStatus = AnimeStatus.WATCHING;
        }
        anime.setStatus(targetStatus);
        // 计划状态从第 0 集开始，追中从第 1 集开始
        anime.setCurrentEpisode(targetStatus == AnimeStatus.PLANNING ? 0 : 1);
        anime.setLegacy(dto.getLegacy() != null && dto.getLegacy());
        anime.setWatchStartDate(parseDate(dto.getWatchStartDate()) != null ? parseDate(dto.getWatchStartDate()) : LocalDate.now());

        Anime saved = animeRepository.save(anime);

        // 非 PLANNING 状态时，为每集创建观看记录
        if (targetStatus != AnimeStatus.PLANNING && saved.getCurrentEpisode() != null && saved.getCurrentEpisode() > 0) {
            for (int ep = 1; ep <= saved.getCurrentEpisode(); ep++) {
                saveRecordIfAbsent(saved.getId(), ep, LocalDate.now());
            }
        }

        return toVO(saved);
    }

    @Override
    public AnimeVO nextEpisode(Long id) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        if (anime.getCurrentEpisode() >= anime.getTotalEpisodes()) {
            throw new IllegalArgumentException("reached_max");
        }

        int newEp = anime.getCurrentEpisode() + 1;
        anime.setCurrentEpisode(newEp);
        // 自动设置追番开始日
        if (anime.getWatchStartDate() == null) {
            anime.setWatchStartDate(LocalDate.now());
        }
        if (anime.getCurrentEpisode().equals(anime.getTotalEpisodes())) {
            anime.setStatus(AnimeStatus.FINISHED);
            anime.setEndDate(LocalDate.now());
        } else {
            anime.setStatus(AnimeStatus.WATCHING);
        }

        // 记录这一集的观看
        saveRecordIfAbsent(id, newEp, LocalDate.now());

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO prevEpisode(Long id) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        if (anime.getCurrentEpisode() <= 1) {
            throw new IllegalArgumentException("reached_min");
        }

        int removedEp = anime.getCurrentEpisode();
        anime.setCurrentEpisode(removedEp - 1);
        anime.setStatus(AnimeStatus.WATCHING);
        anime.setEndDate(null);

        // 删除这一集的观看记录
        episodeRecordRepository.deleteByAnimeIdAndEpisodeNumber(id, removedEp);

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO updateAnime(Long id, AnimeUpdateDTO dto) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

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
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        anime.setStatus(status);
        if (status == AnimeStatus.FINISHED) {
            anime.setEndDate(LocalDate.now());
        }
        // 从计划改为追中时，自动设置集数为 1
        if (status == AnimeStatus.WATCHING && anime.getCurrentEpisode() == 0) {
            anime.setCurrentEpisode(1);
            if (anime.getWatchStartDate() == null) {
                anime.setWatchStartDate(LocalDate.now());
            }
            saveRecordIfAbsent(id, 1, LocalDate.now());
        }
        return toVO(animeRepository.save(anime));
    }

    @Override
    @Transactional
    public void deleteAnime(Long id) {
        if (!animeRepository.existsById(id)) {
            throw new ResourceNotFoundException("未找到该番剧");
        }
        // 级联删除关联的观看记录，避免孤儿数据
        episodeRecordRepository.deleteByAnimeId(id);
        animeRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        for (Long id : ids) {
            episodeRecordRepository.deleteByAnimeId(id);
        }
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
        Pageable pageable = Pageable.unpaged(SortUtil.buildSort(sortBy));
        Page<AnimeVO> page = doSearch(name, status, pageable, tag);
        return page.getContent();
    }

    @Override
    public Page<AnimeVO> searchAnimePaged(String name, AnimeStatus status, Pageable pageable, String tag) {
        return doSearch(name, status, pageable, tag);
    }

    private Page<AnimeVO> doSearch(String name, AnimeStatus status, Pageable pageable, String tag) {
        boolean hasTag = tag != null && !tag.trim().isEmpty();
        if (hasTag) {
            Page<Anime> page = animeRepository.findByTagContaining(tag.trim(), pageable);
            return page.map(this::toVO);
        }

        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasStatus = status != null;
        Page<Anime> page;

        if (hasName && hasStatus) {
            page = animeRepository.findByNameContainingAndStatus(name, status, pageable);
        } else if (hasName) {
            page = animeRepository.findByNameContaining(name, pageable);
        } else if (hasStatus) {
            page = animeRepository.findByStatus(status, pageable);
        } else {
            page = animeRepository.findAll(pageable);
        }

        return page.map(this::toVO);
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
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.addMixIn(Anime.class, ExportMixIn.class);
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all);
        } catch (Exception e) {
            throw new RuntimeException("导出失败", e);
        }
    }

    // 导出时忽略的字段
    abstract static class ExportMixIn {
        @com.fasterxml.jackson.annotation.JsonIgnore abstract Long getId();
        @com.fasterxml.jackson.annotation.JsonIgnore abstract java.time.LocalDateTime getCreatedAt();
        @com.fasterxml.jackson.annotation.JsonIgnore abstract java.time.LocalDateTime getUpdatedAt();
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

                Anime saved = animeRepository.save(anime);
                result.add(toVO(saved));

                // 为导入的集数创建观看记录
                if (!saved.isLegacy() && saved.getCurrentEpisode() != null && saved.getCurrentEpisode() > 0) {
                    episodeRecordRepository.deleteByAnimeId(saved.getId());
                    LocalDate baseDate = saved.getWatchStartDate() != null ? saved.getWatchStartDate() : LocalDate.now();
                    for (int ep = 1; ep <= saved.getCurrentEpisode(); ep++) {
                        saveRecordIfAbsent(saved.getId(), ep, baseDate);
                    }
                }

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
                if (days >= 0) {
                    totalWatched += a.getCurrentEpisode();
                    totalDays += Math.max(days, 1); // 同一天视为 1 天
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

        // Watch duration stats — 每部番的观看天数
        List<Map<String, Object>> durationList = new ArrayList<>();
        for (Anime a : all) {
            if (a.isLegacy()) continue;
            LocalDate watchStart = a.getWatchStartDate() != null ? a.getWatchStartDate() : (a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : null);
            if (watchStart == null) continue;
            LocalDate end = a.getEndDate() != null ? a.getEndDate() : (a.getStatus() == AnimeStatus.FINISHED ? (a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : LocalDate.now()) : LocalDate.now());
            long days = java.time.temporal.ChronoUnit.DAYS.between(watchStart, end);
            if (days < 0) days = 0;
            Map<String, Object> d = new HashMap<>();
            d.put("name", a.getName());
            d.put("days", days);
            d.put("episodes", a.getCurrentEpisode());
            d.put("status", a.getStatus().name().toLowerCase());
            d.put("score", a.getScore());
            durationList.add(d);
        }
        durationList.sort((a, b) -> Long.compare((long) b.get("days"), (long) a.get("days")));
        stats.put("watchDuration", durationList);

        // Monthly completion stats — 按月统计完成的番剧
        Map<String, Integer> monthlyCompleted = new HashMap<>();
        Map<String, Double> monthlyScores = new HashMap<>();
        for (Anime a : all) {
            if (a.getStatus() == AnimeStatus.FINISHED && a.getEndDate() != null) {
                String month = a.getEndDate().toString().substring(0, 7); // YYYY-MM
                monthlyCompleted.merge(month, 1, Integer::sum);
                monthlyScores.computeIfAbsent(month, k -> 0.0);
                // 累加评分用于计算平均值
                if (a.getScore() != null && a.getScore() > 0) {
                    monthlyScores.merge(month, a.getScore(), Double::sum);
                }
            }
        }
        // 计算平均评分
        Map<String, Map<String, Object>> monthlyReport = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : monthlyCompleted.entrySet()) {
            String month = e.getKey();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("count", e.getValue());
            double totalScore = monthlyScores.getOrDefault(month, 0.0);
            report.put("avgScore", e.getValue() > 0 ? Math.round(totalScore / e.getValue() * 10.0) / 10.0 : 0);
            monthlyReport.put(month, report);
        }
        stats.put("monthlyReport", monthlyReport);

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
    public AnimeVO matchBangumi(Long id) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        if (anime.getBangumiId() != null) {
            return toVO(anime);
        }

        List<BangumiResult> results = bangumiService.search(anime.getName(), 5);
        for (BangumiResult r : results) {
            if (anime.getName().equalsIgnoreCase(r.getName())
                    || anime.getName().equalsIgnoreCase(r.getNameCn())) {
                anime.setBangumiId(r.getId());
                if (anime.getCoverUrl() == null && r.getImage() != null) {
                    anime.setCoverUrl(r.getImage());
                }
                return toVO(animeRepository.save(anime));
            }
        }
        throw new IllegalArgumentException("未在 Bangumi 找到匹配结果");
    }

    @Override
    @Transactional
    public Map<String, Object> batchMatchBangumi() {
        List<Anime> noBangumi = animeRepository.findByBangumiIdIsNull();
        int matched = 0;
        int failed = 0;
        for (Anime anime : noBangumi) {
            try {
                matchBangumi(anime.getId());
                matched++;
                Thread.sleep(500);
            } catch (Exception e) {
                failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", matched);
        result.put("failed", failed);
        result.put("total", noBangumi.size());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> importFromBangumi(String username) {
        List<Map<String, Object>> collections = bangumiService.getUserCollections(username, 200);
        int created = 0;
        int skipped = 0;

        for (Map<String, Object> item : collections) {
            Integer subjectId = item.get("subjectId") instanceof Number
                    ? ((Number) item.get("subjectId")).intValue() : null;
            String name = (String) item.get("name");
            String nameCn = (String) item.get("nameCn");

            // 使用中文名优先，没有则用原名
            String displayName = (nameCn != null && !nameCn.isEmpty()) ? nameCn : name;
            if (displayName == null || displayName.trim().isEmpty()) continue;

            // 按 bangumiId 去重
            if (subjectId != null && animeRepository.existsByBangumiId(subjectId)) {
                skipped++;
                continue;
            }
            // 按名称去重
            if (animeRepository.existsByName(displayName)) {
                skipped++;
                continue;
            }

            // 状态映射: 1=wish→PLANNING, 2=watched→FINISHED, 3=watching→WATCHING, 5=dropped→DROPPED
            int type = item.get("type") instanceof Number ? ((Number) item.get("type")).intValue() : 3;
            AnimeStatus status = switch (type) {
                case 1 -> AnimeStatus.PLANNING;
                case 2 -> AnimeStatus.FINISHED;
                case 5 -> AnimeStatus.DROPPED;
                default -> AnimeStatus.WATCHING;
            };

            // 集数
            int totalEps = item.get("eps") instanceof Number ? ((Number) item.get("eps")).intValue() : 12;
            int epStatus = item.get("epStatus") instanceof Number ? ((Number) item.get("epStatus")).intValue() : 0;

            Anime anime = new Anime();
            anime.setName(displayName);
            anime.setBangumiId(subjectId);
            anime.setTotalEpisodes(totalEps > 0 ? totalEps : 12);
            anime.setCurrentEpisode(epStatus);
            anime.setStatus(status);
            anime.setCoverUrl((String) item.get("image"));
            anime.setSeason(guessSeason((String) item.get("date")));
            anime.setScore(0.0);
            anime.setRemark("");
            anime.setWatchStartDate(LocalDate.now());

            Anime saved = animeRepository.save(anime);

            // 为导入的集数创建观看记录
            if (epStatus > 0 && !saved.isLegacy()) {
                for (int ep = 1; ep <= epStatus; ep++) {
                    saveRecordIfAbsent(saved.getId(), ep, LocalDate.now());
                }
            }

            created++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("total", collections.size());
        return result;
    }

    @Override
    public List<Map<String, Object>> getRecommendations() {
        List<Anime> all = animeRepository.findAll();

        // 统计用户标签频率
        Map<String, Integer> tagCounts = new HashMap<>();
        Set<String> trackedNames = new HashSet<>();
        Set<Integer> trackedBangumiIds = new HashSet<>();
        for (Anime a : all) {
            trackedNames.add(a.getName().toLowerCase());
            if (a.getBangumiId() != null) trackedBangumiIds.add(a.getBangumiId());
            if (a.getTags() != null && !a.getTags().trim().isEmpty()) {
                for (String tag : a.getTags().split(",")) {
                    String t = tag.trim();
                    if (!t.isEmpty()) tagCounts.merge(t, 1, Integer::sum);
                }
            }
        }

        // 取 TOP 3 标签
        List<String> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (topTags.isEmpty()) return List.of();

        // 用标签搜索 Bangumi
        List<Map<String, Object>> recommendations = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (String tag : topTags) {
            try {
                List<BangumiResult> results = bangumiService.searchByTag(tag, 10);
                for (BangumiResult r : results) {
                    if (trackedBangumiIds.contains(r.getId())) continue;
                    if (seenIds.contains(r.getId())) continue;
                    if (trackedNames.contains(r.getName().toLowerCase())) continue;
                    if (r.getNameCn() != null && trackedNames.contains(r.getNameCn().toLowerCase())) continue;

                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", r.getId());
                    rec.put("name", r.getName());
                    rec.put("nameCn", r.getNameCn());
                    rec.put("image", r.getImage());
                    rec.put("score", r.getScore());
                    rec.put("date", r.getDate());
                    rec.put("reason", "标签: " + tag);
                    recommendations.add(rec);
                    seenIds.add(r.getId());

                    if (recommendations.size() >= 10) return recommendations;
                }
            } catch (Exception e) {
                // 单个标签搜索失败不影响其他标签
            }
        }
        return recommendations;
    }

    @Override
    public Map<String, Integer> getHeatmap() {
        Map<String, Integer> heatmap = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate oneYearAgo = today.minusYears(1);

        // 初始化过去一年每天为 0
        for (LocalDate d = oneYearAgo; !d.isAfter(today); d = d.plusDays(1)) {
            heatmap.put(d.toString(), 0);
        }

        // 优先从 episode_record 聚合（事件驱动，精确记录）
        List<Object[]> rows = episodeRecordRepository.countByWatchedDateBetween(oneYearAgo, today);
        if (!rows.isEmpty()) {
            for (Object[] row : rows) {
                LocalDate date = (LocalDate) row[0];
                long count = (long) row[1];
                heatmap.merge(date.toString(), (int) count, Integer::sum);
            }
            return heatmap;
        }

        // Fallback：旧估算逻辑（兼容尚无 episode_record 的数据）
        return getHeatmapLegacyFallback(heatmap, today, oneYearAgo);
    }

    private Map<String, Integer> getHeatmapLegacyFallback(Map<String, Integer> heatmap, LocalDate today, LocalDate oneYearAgo) {
        List<Anime> all = animeRepository.findAll();
        for (Anime a : all) {
            if (a.isLegacy()) continue;
            LocalDate start = a.getWatchStartDate();
            if (start == null) continue;
            LocalDate end = a.getEndDate() != null ? a.getEndDate() :
                    (a.getStatus() == AnimeStatus.FINISHED && a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : today);

            if (end.isBefore(oneYearAgo)) continue;
            LocalDate effectiveStart = start.isBefore(oneYearAgo) ? oneYearAgo : start;
            if (end.isAfter(today)) end = today;

            long days = java.time.temporal.ChronoUnit.DAYS.between(effectiveStart, end);
            if (days <= 0) days = 1;

            int eps = a.getCurrentEpisode() != null ? a.getCurrentEpisode() : 0;
            if (eps <= 0) continue;

            double epsPerDay = (double) eps / days;
            for (LocalDate d = effectiveStart; !d.isAfter(end); d = d.plusDays(1)) {
                heatmap.merge(d.toString(), (int) Math.ceil(epsPerDay), Integer::sum);
            }
        }
        return heatmap;
    }

    private void saveRecordIfAbsent(Long animeId, int episodeNumber, LocalDate watchedDate) {
        episodeRecordRepository.findByAnimeIdAndEpisodeNumber(animeId, episodeNumber)
                .orElseGet(() -> {
                    EpisodeRecord r = new EpisodeRecord();
                    r.setAnimeId(animeId);
                    r.setEpisodeNumber(episodeNumber);
                    r.setWatchedDate(watchedDate);
                    return episodeRecordRepository.save(r);
                });
    }

    // 根据放送日期猜测季度
    private String guessSeason(String dateStr) {
        if (dateStr == null || dateStr.length() < 4) return "";
        try {
            int year = Integer.parseInt(dateStr.substring(0, 4));
            String month = dateStr.length() >= 7 ? dateStr.substring(5, 7) : "01";
            int m = Integer.parseInt(month);
            String season = m <= 3 ? "冬" : m <= 6 ? "春" : m <= 9 ? "夏" : "秋";
            return year + season;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    @Transactional
    public void reorderAnime(List<Map<String, Object>> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (Map<String, Object> order : orders) {
            Long id = Long.valueOf(order.get("id").toString());
            Integer sortOrder = Integer.valueOf(order.get("sortOrder").toString());
            animeRepository.updateSortOrderById(id, sortOrder);
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
