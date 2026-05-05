package com.otakulog.service;

import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiringScheduleService {

    private final BangumiService bangumiService;
    private final AnimeRepository animeRepository;

    public AiringScheduleService(BangumiService bangumiService, AnimeRepository animeRepository) {
        this.bangumiService = bangumiService;
        this.animeRepository = animeRepository;
    }

    public Map<String, Object> getAiringSchedule() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Get Bangumi calendar (cached) — fetch first so we can match
        Map<Integer, List<Map<String, Object>>> bangumiByDay = new LinkedHashMap<>();
        for (int day = 1; day <= 7; day++) bangumiByDay.put(day, new ArrayList<>());

        // Build name->weekday lookup from Bangumi calendar
        Map<String, Integer> bangumiNameToDay = new HashMap<>();
        Map<String, Integer> bangumiNameCnToDay = new HashMap<>();

        try {
            List<Map<String, Object>> bangumiCalendar = bangumiService.getCalendar();

            // Track all tracked anime bangumiIds
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

                // Build lookup maps
                if (name != null) bangumiNameToDay.put(name.toLowerCase().trim(), weekday);
                if (nameCn != null && !nameCn.isEmpty()) bangumiNameCnToDay.put(nameCn.toLowerCase().trim(), weekday);

                Map<String, Object> entry = new LinkedHashMap<>(item);
                entry.put("isTracked", trackedBangumiIds.contains(bangumiId));
                // Also check by name match
                if (!entry.get("isTracked").equals(true)) {
                    boolean nameMatch = (name != null && trackedNames.contains(name.toLowerCase().trim()))
                            || (nameCn != null && !nameCn.isEmpty() && trackedNames.contains(nameCn.toLowerCase().trim()));
                    if (nameMatch) entry.put("isTracked", true);
                }
                bangumiByDay.computeIfAbsent(weekday, k -> new ArrayList<>()).add(entry);
            }
            result.put("bangumiSchedule", bangumiByDay);

            // Auto-update broadcastDay for tracked anime that don't have it
            for (Anime anime : allTracked) {
                if (anime.getBroadcastDay() == null || anime.getBroadcastDay() == 0) {
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
        } catch (Exception e) {
            result.put("bangumiSchedule", bangumiByDay);
        }

        // Get user's tracked anime grouped by broadcastDay
        Map<Integer, List<Map<String, Object>>> mySchedule = new LinkedHashMap<>();
        Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "name"));
        for (int day = 1; day <= 7; day++) {
            List<Map<String, Object>> items = animeRepository.findByBroadcastDay(day, sort)
                    .stream().map(this::animeToScheduleItem).collect(Collectors.toList());
            mySchedule.put(day, items);
        }
        result.put("mySchedule", mySchedule);

        // Today's day of week (1=Monday..7=Sunday)
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        int todayDay = dow == DayOfWeek.SUNDAY ? 7 : dow.getValue();
        result.put("todayDay", todayDay);

        return result;
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
        return item;
    }
}
