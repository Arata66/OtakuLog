package com.otakulog.service.impl;

import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.service.AiringScheduleService;
import com.otakulog.service.BangumiService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiringScheduleServiceImpl implements AiringScheduleService {

    private final BangumiService bangumiService;
    private final AnimeRepository animeRepository;

    public AiringScheduleServiceImpl(BangumiService bangumiService, AnimeRepository animeRepository) {
        this.bangumiService = bangumiService;
        this.animeRepository = animeRepository;
    }

    @Override
    public Map<String, Object> getAiringSchedule() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<Integer, List<Map<String, Object>>> bangumiByDay = new LinkedHashMap<>();
        for (int day = 1; day <= 7; day++) bangumiByDay.put(day, new ArrayList<>());

        Map<String, Integer> bangumiNameToDay = new HashMap<>();
        Map<String, Integer> bangumiNameCnToDay = new HashMap<>();

        Set<Integer> currentSeasonBangumiIds = new HashSet<>();
        Set<String> currentSeasonNames = new HashSet<>();
        boolean bangumiLoaded = false;

        try {
            List<Map<String, Object>> bangumiCalendar = bangumiService.getCalendar();

            List<Anime> allTracked = animeRepository.findAll();
            Set<Integer> trackedBangumiIds = allTracked.stream()
                    .filter(a -> a.getBangumiId() != null)
                    .map(Anime::getBangumiId)
                    .collect(Collectors.toSet());
            Set<String> trackedNames = allTracked.stream()
                    .map(a -> a.getName().toLowerCase().trim())
                    .collect(Collectors.toSet());

            for (Map<String, Object> item : bangumiCalendar) {
                int weekday = ((Number) item.get("weekday")).intValue();
                String name = (String) item.get("name");
                String nameCn = (String) item.get("nameCn");
                int bangumiId = ((Number) item.get("id")).intValue();

                currentSeasonBangumiIds.add(bangumiId);
                if (name != null) currentSeasonNames.add(name.toLowerCase().trim());
                if (nameCn != null && !nameCn.isEmpty()) currentSeasonNames.add(nameCn.toLowerCase().trim());

                if (name != null) bangumiNameToDay.put(name.toLowerCase().trim(), weekday);
                if (nameCn != null && !nameCn.isEmpty()) bangumiNameCnToDay.put(nameCn.toLowerCase().trim(), weekday);

                Map<String, Object> entry = new LinkedHashMap<>(item);
                entry.put("isTracked", trackedBangumiIds.contains(bangumiId));
                if (!entry.get("isTracked").equals(true)) {
                    boolean nameMatch = (name != null && trackedNames.contains(name.toLowerCase().trim()))
                            || (nameCn != null && !nameCn.isEmpty() && trackedNames.contains(nameCn.toLowerCase().trim()));
                    if (nameMatch) entry.put("isTracked", true);
                }
                String airDate = (String) entry.get("airDate");
                if (airDate != null) {
                    try {
                        LocalDate air = LocalDate.parse(airDate);
                        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), air);
                        entry.put("daysUntilAir", days);
                    } catch (Exception ignored) {}
                }
                bangumiByDay.computeIfAbsent(weekday, k -> new ArrayList<>()).add(entry);
            }
            result.put("bangumiSchedule", bangumiByDay);
            bangumiLoaded = true;

            // 只为本季番自动更新 broadcastDay
            for (Anime anime : allTracked) {
                if (anime.getBroadcastDay() == null || anime.getBroadcastDay() == 0) {
                    boolean isCurrentSeason = (anime.getBangumiId() != null && currentSeasonBangumiIds.contains(anime.getBangumiId()))
                            || (anime.getName() != null && currentSeasonNames.contains(anime.getName().toLowerCase().trim()));
                    if (!isCurrentSeason) continue;
                    Integer day = null;
                    if (anime.getName() != null) {
                        day = bangumiNameToDay.get(anime.getName().toLowerCase().trim());
                        if (day == null) day = bangumiNameCnToDay.get(anime.getName().toLowerCase().trim());
                    }
                    if (day != null) {
                        anime.setBroadcastDay(day);
                        animeRepository.save(anime);
                    }
                }
            }

            Map<Integer, List<Map<String, Object>>> mySchedule = new LinkedHashMap<>();
            Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "name"));
            for (int day = 1; day <= 7; day++) {
                List<Map<String, Object>> items = animeRepository.findByBroadcastDay(day, sort)
                        .stream()
                        .filter(a -> isCurrentSeason(a, currentSeasonBangumiIds, currentSeasonNames))
                        .map(this::animeToScheduleItem)
                        .collect(Collectors.toList());
                mySchedule.put(day, items);
            }
            result.put("mySchedule", mySchedule);
        } catch (Exception e) {
            result.put("bangumiSchedule", bangumiByDay);
            if (!bangumiLoaded) {
                Map<Integer, List<Map<String, Object>>> mySchedule = new LinkedHashMap<>();
                Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "name"));
                for (int day = 1; day <= 7; day++) {
                    List<Map<String, Object>> items = animeRepository.findByBroadcastDay(day, sort)
                            .stream().map(this::animeToScheduleItem).collect(Collectors.toList());
                    mySchedule.put(day, items);
                }
                result.put("mySchedule", mySchedule);
            }
        }

        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        int todayDay = dow == DayOfWeek.SUNDAY ? 7 : dow.getValue();
        result.put("todayDay", todayDay);

        return result;
    }

    private boolean isCurrentSeason(Anime a, Set<Integer> currentSeasonBangumiIds, Set<String> currentSeasonNames) {
        if (a.getBangumiId() != null && currentSeasonBangumiIds.contains(a.getBangumiId())) {
            return true;
        }
        if (a.getName() != null && currentSeasonNames.contains(a.getName().toLowerCase().trim())) {
            return true;
        }
        return false;
    }

    private Map<String, Object> animeToScheduleItem(Anime a) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", a.getId());
        item.put("name", a.getName());
        item.put("coverUrl", a.getCoverUrl());
        item.put("currentEpisode", a.getCurrentEpisode());
        item.put("totalEpisodes", a.getTotalEpisodes());
        item.put("status", a.getStatus().name().toLowerCase());
        item.put("bangumiId", a.getBangumiId());
        if (a.getTotalEpisodes() != null && a.getTotalEpisodes() > 0) {
            item.put("progress", Math.round((double) a.getCurrentEpisode() / a.getTotalEpisodes() * 1000.0) / 10.0);
        }
        if (a.getStartDate() != null) {
            item.put("airDate", a.getStartDate().toString());
            long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), a.getStartDate());
            item.put("daysUntilAir", days);
        }
        return item;
    }
}
