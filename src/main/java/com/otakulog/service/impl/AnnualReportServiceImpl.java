package com.otakulog.service.impl;

import com.otakulog.dto.AnnualReportDTO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.Tag;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.TagRepository;
import com.otakulog.service.AnnualReportService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnnualReportServiceImpl implements AnnualReportService {

    private final AnimeRepository animeRepository;
    private final TagRepository tagRepository;

    public AnnualReportServiceImpl(AnimeRepository animeRepository, TagRepository tagRepository) {
        this.animeRepository = animeRepository;
        this.tagRepository = tagRepository;
    }

    @Override
    public AnnualReportDTO getAnnualReport(int year) {
        AnnualReportDTO report = new AnnualReportDTO();
        report.setYear(year);

        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        // 查询该年度完成的番剧
        List<Anime> finishedAnimes = animeRepository.findAll().stream()
                .filter(a -> a.getStatus() == AnimeStatus.FINISHED && a.getEndDate() != null)
                .filter(a -> {
                    LocalDate endDate = a.getEndDate();
                    return !endDate.isBefore(yearStart) && !endDate.isAfter(yearEnd);
                })
                .collect(Collectors.toList());

        // 总观看数
        report.setTotalWatched(finishedAnimes.size());

        // 总集数
        long totalEpisodes = finishedAnimes.stream()
                .mapToLong(a -> a.getTotalEpisodes() != null ? a.getTotalEpisodes() : 0)
                .sum();
        report.setTotalEpisodes(totalEpisodes);

        // 平均评分
        double avgRating = finishedAnimes.stream()
                .filter(a -> a.getScore() != null && a.getScore() > 0)
                .mapToDouble(Anime::getScore)
                .average()
                .orElse(0.0);
        report.setAverageRating(Math.round(avgRating * 10.0) / 10.0);

        // 观看时长（假设每集 24 分钟）
        double watchingHours = totalEpisodes * 24.0 / 60.0;
        report.setWatchingHours(Math.round(watchingHours * 10.0) / 10.0);

        // 月度统计
        Map<String, Map<String, Object>> monthlyStats = new LinkedHashMap<>();
        for (int month = 1; month <= 12; month++) {
            monthlyStats.put(String.valueOf(month), new HashMap<>());
        }

        for (Anime anime : finishedAnimes) {
            if (anime.getEndDate() != null) {
                String month = String.valueOf(anime.getEndDate().getMonthValue());
                Map<String, Object> stats = monthlyStats.get(month);
                stats.put("count", (int) stats.getOrDefault("count", 0) + 1);
                if (anime.getScore() != null && anime.getScore() > 0) {
                    double totalScore = (double) stats.getOrDefault("totalScore", 0.0);
                    stats.put("totalScore", totalScore + anime.getScore());
                }
            }
        }

        List<Map<String, Object>> monthlyList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : monthlyStats.entrySet()) {
            Map<String, Object> stats = entry.getValue();
            int count = (int) stats.getOrDefault("count", 0);
            double totalScore = (double) stats.getOrDefault("totalScore", 0.0);
            double avgScore = count > 0 ? totalScore / count : 0.0;

            Map<String, Object> monthData = new HashMap<>();
            monthData.put("month", entry.getKey());
            monthData.put("count", count);
            monthData.put("avgScore", Math.round(avgScore * 10.0) / 10.0);
            monthlyList.add(monthData);
        }
        report.setMonthlyStats(monthlyList);

        // TOP 番剧（按评分排序）
        List<Map<String, Object>> topAnimes = finishedAnimes.stream()
                .filter(a -> a.getScore() != null && a.getScore() > 0)
                .sorted((a1, a2) -> Double.compare(a2.getScore(), a1.getScore()))
                .limit(10)
                .map(a -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", a.getName());
                    map.put("score", a.getScore());
                    map.put("episodes", a.getTotalEpisodes());
                    map.put("season", a.getSeason());
                    return map;
                })
                .collect(Collectors.toList());
        report.setTopAnimes(topAnimes);

        // 标签分布
        Map<String, Integer> tagCounts = new HashMap<>();
        for (Anime anime : finishedAnimes) {
            if (anime.getTags() != null) {
                for (Tag tag : anime.getTags()) {
                    tagCounts.merge(tag.getName(), 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> tagDistribution = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("tag", e.getKey());
                    map.put("count", e.getValue());
                    return map;
                })
                .collect(Collectors.toList());
        report.setTagDistribution(tagDistribution);

        // 评分分布
        Map<String, Integer> ratingBuckets = new LinkedHashMap<>();
        ratingBuckets.put("0-2", 0);
        ratingBuckets.put("2-4", 0);
        ratingBuckets.put("4-6", 0);
        ratingBuckets.put("6-8", 0);
        ratingBuckets.put("8-10", 0);

        for (Anime anime : finishedAnimes) {
            if (anime.getScore() != null && anime.getScore() > 0) {
                double score = anime.getScore();
                if (score < 2) ratingBuckets.merge("0-2", 1, Integer::sum);
                else if (score < 4) ratingBuckets.merge("2-4", 1, Integer::sum);
                else if (score < 6) ratingBuckets.merge("4-6", 1, Integer::sum);
                else if (score < 8) ratingBuckets.merge("6-8", 1, Integer::sum);
                else ratingBuckets.merge("8-10", 1, Integer::sum);
            }
        }

        List<Map<String, Object>> ratingDistribution = ratingBuckets.entrySet().stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("range", e.getKey());
                    map.put("count", e.getValue());
                    return map;
                })
                .collect(Collectors.toList());
        report.setRatingDistribution(ratingDistribution);

        return report;
    }

    @Override
    public AnnualReportDTO getLatestAnnualReport() {
        int currentYear = LocalDate.now().getYear();
        return getAnnualReport(currentYear);
    }
}
