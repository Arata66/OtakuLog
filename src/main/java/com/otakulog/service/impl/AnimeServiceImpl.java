package com.otakulog.service.impl;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.service.AnimeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnimeServiceImpl implements AnimeService {

    @Autowired
    private AnimeRepository animeRepository;

    @Override
    public AnimeVO addAnime(AnimeDTO dto) {
        Anime anime = new Anime();
        anime.setName(dto.getName());
        anime.setTotalEpisodes(dto.getTotalEpisodes());
        anime.setSeason(dto.getSeason());
        anime.setScore(dto.getScore());
        anime.setRemark(dto.getRemark() != null ? dto.getRemark() : "");
        anime.setCoverUrl(dto.getCoverUrl());
        anime.setStartDate(parseDate(dto.getStartDate()));
        anime.setEndDate(parseDate(dto.getEndDate()));
        anime.setCurrentEpisode(1);
        anime.setStatus(AnimeStatus.WATCHING);

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
        anime.setSeason(dto.getSeason());
        anime.setScore(dto.getScore());
        anime.setRemark(dto.getRemark() != null ? dto.getRemark() : "");
        anime.setCoverUrl(dto.getCoverUrl());
        anime.setStartDate(parseDate(dto.getStartDate()));
        anime.setEndDate(parseDate(dto.getEndDate()));

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
    public List<AnimeVO> searchAnime(String name, AnimeStatus status, String sortBy) {
        List<Anime> results;
        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasStatus = status != null;

        if (hasName && hasStatus) {
            results = animeRepository.findByNameContainingAndStatus(name, status);
        } else if (hasName) {
            results = animeRepository.findByNameContaining(name);
        } else if (hasStatus) {
            results = animeRepository.findByStatus(status);
        } else {
            results = animeRepository.findAll();
        }

        return sortAndConvert(results, sortBy);
    }

    @Override
    public Page<AnimeVO> searchAnimePaged(String name, AnimeStatus status, Pageable pageable) {
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

        long total = animeRepository.count();
        long watching = animeRepository.countByStatus(AnimeStatus.WATCHING);
        long finished = animeRepository.countByStatus(AnimeStatus.FINISHED);
        long planning = animeRepository.countByStatus(AnimeStatus.PLANNING);
        long dropped = animeRepository.countByStatus(AnimeStatus.DROPPED);

        stats.put("total", total);
        stats.put("watching", watching);
        stats.put("finished", finished);
        stats.put("planning", planning);
        stats.put("dropped", dropped);

        long totalEpisodes = animeRepository.sumTotalEpisodes();
        long watchedEpisodes = animeRepository.sumCurrentEpisodes();
        double progressPercentage = totalEpisodes > 0 ? (watchedEpisodes * 100.0 / totalEpisodes) : 0;
        stats.put("totalEpisodes", totalEpisodes);
        stats.put("watchedEpisodes", watchedEpisodes);
        stats.put("progressPercentage", Math.round(progressPercentage * 10.0) / 10.0);

        Double avgScore = animeRepository.averageScore();
        stats.put("averageScore", Math.round(avgScore * 10.0) / 10.0);

        stats.put("highScore", animeRepository.countHighScore());
        stats.put("mediumScore", animeRepository.countMediumScore());
        stats.put("lowScore", animeRepository.countLowScore());

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
    public List<AnimeVO> getTimeline() {
        return animeRepository.findAll(Sort.by(Sort.Direction.DESC, "startDate")).stream()
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
                exportList.add(map);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportList);
        } catch (Exception e) {
            throw new RuntimeException("导出失败", e);
        }
    }

    @Override
    public List<AnimeVO> importJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<>() {});
            List<AnimeVO> result = new ArrayList<>();
            for (Map<String, Object> map : list) {
                Anime anime = new Anime();
                anime.setName((String) map.get("name"));
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
                result.add(toVO(animeRepository.save(anime)));
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的状态值: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("导入失败: JSON 格式错误", e);
        }
    }

    private List<AnimeVO> sortAndConvert(List<Anime> list, String sortBy) {
        List<Anime> sorted = new ArrayList<>(list);

        switch (sortBy != null ? sortBy : "id-desc") {
            case "score-desc" -> sorted.sort((a, b) -> {
                if (a.getScore() == null) return 1;
                if (b.getScore() == null) return -1;
                return b.getScore().compareTo(a.getScore());
            });
            case "score-asc" -> sorted.sort((a, b) -> {
                if (a.getScore() == null) return -1;
                if (b.getScore() == null) return 1;
                return a.getScore().compareTo(b.getScore());
            });
            case "progress-desc" -> sorted.sort((a, b) -> {
                double pa = (double) a.getCurrentEpisode() / a.getTotalEpisodes();
                double pb = (double) b.getCurrentEpisode() / b.getTotalEpisodes();
                return Double.compare(pb, pa);
            });
            case "progress-asc" -> sorted.sort((a, b) -> {
                double pa = (double) a.getCurrentEpisode() / a.getTotalEpisodes();
                double pb = (double) b.getCurrentEpisode() / b.getTotalEpisodes();
                return Double.compare(pa, pb);
            });
            case "name-asc" -> sorted.sort(Comparator.comparing(Anime::getName));
            default -> sorted.sort(Comparator.comparing(Anime::getId).reversed());
        }

        return sorted.stream().map(this::toVO).collect(Collectors.toList());
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

    private Double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Double d) return d;
        if (obj instanceof Number n) return n.doubleValue();
        return Double.parseDouble(obj.toString());
    }
}
