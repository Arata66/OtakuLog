package com.otakulog.service.impl;

import com.otakulog.dto.AnnualReportDTO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.Tag;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.service.AnnualReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AnnualReportServiceImpl implements AnnualReportService {
    private static final int MINUTES_PER_EPISODE = 24;
    private static final Set<EpisodeRecordSource> DATED_SOURCES = EnumSet.of(
            EpisodeRecordSource.WATCHED, EpisodeRecordSource.MANUAL, EpisodeRecordSource.IMPORT);
    private final AnimeRepository animeRepository;
    private final EpisodeRecordRepository episodeRepository;

    public AnnualReportServiceImpl(AnimeRepository animeRepository, EpisodeRecordRepository episodeRepository) {
        this.animeRepository = animeRepository;
        this.episodeRepository = episodeRepository;
    }

    @Override
    public AnnualReportDTO getAnnualReport(int year) {
        if (year < 1000 || year > 9999) throw new IllegalArgumentException("年份必须在 1000 到 9999 之间");
        AnnualReportDTO report = new AnnualReportDTO();
        report.setYear(year);
        List<Anime> allAnime = animeRepository.findAll();
        Map<Long, Anime> animeById = allAnime.stream().collect(Collectors.toMap(Anime::getId, a -> a));
        List<Anime> finished = allAnime.stream()
                .filter(a -> a.getStatus() == AnimeStatus.FINISHED && inYear(a.getEndDate(), year)).toList();
        List<Anime> rated = finished.stream().filter(this::hasValidRating).toList();
        report.setTotalWatched(finished.size());
        report.setRatedAnimeCount(rated.size());
        report.setAverageRating(round(rated.stream().mapToDouble(Anime::getScore).average().orElse(0)));
        report.setUndatedFinishedAnimeCount(allAnime.stream()
                .filter(a -> a.getStatus() == AnimeStatus.FINISHED && a.getEndDate() == null).count());

        int[] completedByMonth = new int[12], ratedByMonth = new int[12];
        double[] scoreByMonth = new double[12];
        long[] episodesByMonth = new long[12], legacyByMonth = new long[12];
        for (Anime a : finished) {
            int month = a.getEndDate().getMonthValue() - 1;
            completedByMonth[month]++;
            if (hasValidRating(a)) { ratedByMonth[month]++; scoreByMonth[month] += a.getScore(); }
        }

        long datedEpisodes = 0, legacyEpisodes = 0, undatedRecords = 0;
        Set<Long> watchedAnime = new HashSet<>();
        Map<Long, Long> progressRecords = new HashMap<>();
        for (var record : episodeRepository.findAll()) {
            Anime a = animeById.get(record.getAnimeId());
            if (a == null) continue;
            if (record.getEpisodeNumber() > 0 && record.getEpisodeNumber() <= progress(a))
                progressRecords.merge(a.getId(), 1L, Long::sum);
            LocalDate date = record.getWatchedDate();
            if (date == null) { undatedRecords++; continue; }
            if (!inYear(date, year)) continue;
            int month = date.getMonthValue() - 1;
            // V5 的历史日期可能来自估算，不能在年报中升级为确定观看事实。
            if (record.getSource() == EpisodeRecordSource.LEGACY) {
                legacyEpisodes++; legacyByMonth[month]++;
            } else if (DATED_SOURCES.contains(record.getSource())) {
                datedEpisodes++; episodesByMonth[month]++; watchedAnime.add(a.getId());
            }
        }
        report.setTotalEpisodes(datedEpisodes);
        report.setWatchedAnimeCount(watchedAnime.size());
        report.setLegacyDatedEpisodes(legacyEpisodes);
        report.setUndatedEpisodeRecords(undatedRecords);
        report.setMissingEpisodeRecords(allAnime.stream()
                .mapToLong(a -> Math.max(0, progress(a) - progressRecords.getOrDefault(a.getId(), 0L))).sum());
        report.setMinutesPerEpisode(MINUTES_PER_EPISODE);
        report.setWatchingHoursEstimated(true);
        report.setWatchingHours(round(datedEpisodes * MINUTES_PER_EPISODE / 60.0));

        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int month = 0; month < 12; month++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", String.valueOf(month + 1)); row.put("count", completedByMonth[month]);
            row.put("ratedCount", ratedByMonth[month]);
            row.put("avgScore", ratedByMonth[month] == 0 ? 0.0 : round(scoreByMonth[month] / ratedByMonth[month]));
            row.put("episodes", episodesByMonth[month]); row.put("legacyEpisodes", legacyByMonth[month]);
            monthly.add(row);
        }
        report.setMonthlyStats(monthly);
        report.setTopAnimes(rated.stream().sorted(Comparator.comparing(Anime::getScore).reversed()
                .thenComparing(Anime::getName).thenComparing(Anime::getId)).limit(10).map(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", a.getName()); row.put("score", a.getScore());
                    row.put("episodes", a.getTotalEpisodes()); row.put("season", a.getSeason());
                    return row;
                }).toList());

        Map<String, Integer> tagCounts = new HashMap<>();
        for (Anime a : finished) for (Tag tag : a.getTags()) tagCounts.merge(tag.getName(), 1, Integer::sum);
        report.setTagDistribution(tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(15).map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(); row.put("tag", e.getKey()); row.put("count", e.getValue()); return row;
                }).toList());
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (String range : List.of("0-2", "2-4", "4-6", "6-8", "8-10")) buckets.put(range, 0);
        for (Anime a : rated) {
            double score = a.getScore();
            String range = score < 2 ? "0-2" : score < 4 ? "2-4" : score < 6 ? "4-6" : score < 8 ? "6-8" : "8-10";
            buckets.merge(range, 1, Integer::sum);
        }
        report.setRatingDistribution(buckets.entrySet().stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("range", e.getKey()); row.put("count", e.getValue()); return row;
        }).toList());
        return report;
    }

    @Override
    public AnnualReportDTO getLatestAnnualReport() { return getAnnualReport(LocalDate.now().getYear()); }

    private boolean hasValidRating(Anime a) {
        return a.getScore() != null && Double.isFinite(a.getScore()) && a.getScore() > 0 && a.getScore() <= 10;
    }
    private static boolean inYear(LocalDate date, int year) { return date != null && date.getYear() == year; }
    private static int progress(Anime a) { return a.getCurrentEpisode() == null ? 0 : Math.max(0, a.getCurrentEpisode()); }
    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
}
