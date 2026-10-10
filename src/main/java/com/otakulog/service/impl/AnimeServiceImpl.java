package com.otakulog.service.impl;

import com.otakulog.common.ExternalApiException;
import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.TagDTO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.Tag;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.MemoryInsightRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.repository.TagRepository;
import com.otakulog.dto.BangumiResult;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BangumiService;
import com.otakulog.service.WatchProgressService;
import com.otakulog.util.SortUtil;
import com.otakulog.service.BackupService;
import com.otakulog.service.BangumiImportService;

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
    private final AnimeMemoryRepository memoryRepository;
    private final MemoryInsightRepository insightRepository;
    private final BangumiService bangumiService;
    private final EpisodeRecordRepository episodeRecordRepository;
    private final TagRepository tagRepository;
    private final WatchProgressService watchProgress;
    private final BackupService backup;
    private final BangumiImportService bangumiImport;

    public AnimeServiceImpl(AnimeRepository animeRepository, BangumiService bangumiService,
                            EpisodeRecordRepository episodeRecordRepository, TagRepository tagRepository,
                            WatchProgressService watchProgress, BackupService backup, BangumiImportService bangumiImport,
                            AnimeMemoryRepository memoryRepository, MemoryInsightRepository insightRepository) {
        this.animeRepository = animeRepository;
        this.memoryRepository = memoryRepository;
        this.insightRepository = insightRepository;
        this.bangumiService = bangumiService;
        this.episodeRecordRepository = episodeRecordRepository;
        this.tagRepository = tagRepository;
        this.watchProgress = watchProgress;
        this.backup = backup;
        this.bangumiImport = bangumiImport;
    }

    @Override
    @Transactional
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
        anime.setTags(parseTags(dto.getTags()));
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
        LocalDate watchDate = parseDate(dto.getWatchStartDate());
        anime.setWatchStartDate(watchDate != null ? watchDate
                : (!anime.isLegacy() && targetStatus == AnimeStatus.WATCHING ? LocalDate.now() : null));
        anime.setWatchSeason(dto.getWatchSeason());

        Anime saved = animeRepository.save(anime);

        watchProgress.initialize(saved, watchDate != null);

        return toVO(saved);
    }

    @Override
    @Transactional
    public AnimeVO nextEpisode(Long id) {
        Anime anime = animeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        watchProgress.next(anime);

        return toVO(animeRepository.save(anime));
    }

    @Override
    @Transactional
    public AnimeVO prevEpisode(Long id) {
        Anime anime = animeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        watchProgress.previous(anime);

        return toVO(animeRepository.save(anime));
    }

    @Override
    @Transactional
    public AnimeVO updateAnime(Long id, AnimeUpdateDTO dto) {
        Anime anime = animeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
        AnimeStatus originalStatus = anime.getStatus();

        anime.setName(dto.getName());
        if (dto.getTotalEpisodes() != null) {
            if (dto.getTotalEpisodes() < 1 || dto.getTotalEpisodes() < anime.getCurrentEpisode()) {
                throw new IllegalArgumentException("总集数不能小于当前观看进度");
            }
            anime.setTotalEpisodes(dto.getTotalEpisodes());
            if (anime.getStatus() == AnimeStatus.FINISHED && anime.getCurrentEpisode() < dto.getTotalEpisodes()) {
                anime.setStatus(AnimeStatus.WATCHING);
                anime.setEndDate(null);
            }
        }
        anime.setSeason(dto.getSeason());
        anime.setScore(dto.getScore());
        anime.setRemark(dto.getRemark() != null ? dto.getRemark() : "");
        anime.setCoverUrl(dto.getCoverUrl());
        anime.setStartDate(parseDate(dto.getStartDate()));
        LocalDate completion = parseDate(dto.getEndDate());
        anime.setTags(parseTags(dto.getTags()));
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
        if (dto.getWatchSeason() != null) {
            anime.setWatchSeason(dto.getWatchSeason());
        }

        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            AnimeStatus requested = AnimeStatus.valueOf(dto.getStatus().toUpperCase(Locale.ROOT));
            // 状态未改动时，保留增加总集数后自动恢复追中的结果。
            if (requested != originalStatus) watchProgress.changeStatus(anime, requested);
        }
        if (anime.getStatus() == AnimeStatus.FINISHED) {
            if (completion != null) anime.setEndDate(completion);
        } else {
            anime.setEndDate(null);
        }

        return toVO(animeRepository.save(anime));
    }

    @Override
    @Transactional
    public AnimeVO updateStatus(Long id, AnimeStatus status) {
        Anime anime = animeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));

        watchProgress.changeStatus(anime, status);
        return toVO(animeRepository.save(anime));
    }

    @Override
    @Transactional
    public void deleteAnime(Long id) {
        animeRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
        // 级联删除关联的观看记录，避免孤儿数据
        episodeRecordRepository.deleteByAnimeId(id);
        insightRepository.deleteByAnimeId(id);
        memoryRepository.deleteByAnimeId(id);
        animeRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        for (Long id : ids.stream().distinct().sorted().toList()) {
            // 与记忆写入共用作品锁，避免删除时新增孤儿记录。
            animeRepository.findByIdForUpdate(id);
            episodeRecordRepository.deleteByAnimeId(id);
            insightRepository.deleteByAnimeId(id);
            memoryRepository.deleteByAnimeId(id);
        }
        animeRepository.deleteAllById(ids);
    }

    @Override
    @Transactional
    public void batchUpdateStatus(List<Long> ids, AnimeStatus status) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids.stream().distinct().sorted().toList()) {
            Anime anime = animeRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
            watchProgress.changeStatus(anime, status);
            animeRepository.save(anime);
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
            // 先按名称查找 tag
            Optional<Tag> foundTag = tagRepository.findByName(tag.trim());
            if (foundTag.isPresent()) {
                Page<Anime> page = animeRepository.findByTagId(foundTag.get().getId(), pageable);
                return page.map(this::toVO);
            }
            // 找不到对应标签，返回空结果
            return Page.empty(pageable);
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
    public String exportJson() { return backup.exportJson(); }

    @Override
    public Map<String, Object> importJson(String json) { return backup.importJson(json); }

    @Override
    public Map<String, Object> previewImportJson(String json) { return backup.previewJson(json); }
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

        // Tag breakdown — 从 tag 表统计
        List<Object[]> tagRows = tagRepository.findAllWithCount();
        List<Map<String, Object>> tagList = new ArrayList<>();
        for (Object[] row : tagRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("tag", row[0]);
            m.put("count", ((Number) row[1]).intValue());
            tagList.add(m);
        }
        stats.put("tags", tagList);

        // 后续统计仍需要全量番剧数据
        List<Anime> all = animeRepository.findAll();

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
    public Map<String, Object> importFromBangumi(String username) {
        return bangumiImport.importCollections(username);
    }

    @Override
    public List<Map<String, Object>> getRecommendations() {
        List<Anime> all = animeRepository.findAll();

        // 统计用户标签频率（从 tag 关联表）
        Map<String, Integer> tagCounts = new HashMap<>();
        Set<String> trackedNames = new HashSet<>();
        Set<Integer> trackedBangumiIds = new HashSet<>();
        for (Anime a : all) {
            trackedNames.add(a.getName().toLowerCase());
            if (a.getBangumiId() != null) trackedBangumiIds.add(a.getBangumiId());
            if (a.getTags() != null) {
                for (Tag tag : a.getTags()) {
                    tagCounts.merge(tag.getName(), 1, Integer::sum);
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
        if (episodeRecordRepository.count() > 0) {
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
        return com.otakulog.util.AnimeVOMapper.toVO(anime);
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式必须为 yyyy-MM-dd", e);
        }
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

    // 将逗号分隔的标签字符串转换为 Set<Tag>
    private Set<Tag> parseTags(String tagsStr) {
        Set<Tag> tags = new HashSet<>();
        if (tagsStr == null || tagsStr.trim().isEmpty()) return tags;
        for (String raw : tagsStr.split(",")) {
            String tagName = raw.trim();
            if (tagName.isEmpty()) continue;
            Tag tag = tagRepository.findByName(tagName)
                    .orElseGet(() -> tagRepository.save(new Tag(tagName)));
            tags.add(tag);
        }
        return tags;
    }

    @Override
    public List<TagDTO> getAllTagsWithCount() {
        List<Object[]> rows = tagRepository.findAllWithCount();
        List<TagDTO> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new TagDTO(null, (String) row[0], ((Number) row[1]).intValue()));
        }
        return result;
    }

    @Override
    public List<AnimeVO> getAnimesByTagId(Long tagId) {
        return animeRepository.findByTagId(tagId, Sort.by(Sort.Direction.DESC, "id"))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addTagToAnime(Long animeId, String tagName) {
        Anime anime = animeRepository.findById(animeId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
        if (tagName == null || tagName.trim().isEmpty()) return;
        Tag tag = tagRepository.findByName(tagName.trim())
                .orElseGet(() -> tagRepository.save(new Tag(tagName.trim())));
        anime.getTags().add(tag);
        animeRepository.save(anime);
    }

    @Override
    @Transactional
    public void removeTagFromAnime(Long animeId, Long tagId) {
        Anime anime = animeRepository.findById(animeId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该番剧"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该标签"));
        anime.getTags().remove(tag);
        animeRepository.save(anime);
    }
}
