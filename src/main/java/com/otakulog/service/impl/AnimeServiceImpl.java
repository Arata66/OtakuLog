package com.otakulog.service.impl;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.service.AnimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

        return toVO(animeRepository.save(anime));
    }

    @Override
    public AnimeVO updateStatus(Long id, AnimeStatus status) {
        Anime anime = animeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该番剧"));

        anime.setStatus(status);
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
        List<Anime> allAnime = animeRepository.findAll();
        Map<String, Object> stats = new HashMap<>();

        long total = allAnime.size();
        long watching = allAnime.stream().filter(a -> a.getStatus() == AnimeStatus.WATCHING).count();
        long finished = allAnime.stream().filter(a -> a.getStatus() == AnimeStatus.FINISHED).count();
        long planning = allAnime.stream().filter(a -> a.getStatus() == AnimeStatus.PLANNING).count();
        long dropped = allAnime.stream().filter(a -> a.getStatus() == AnimeStatus.DROPPED).count();

        stats.put("total", total);
        stats.put("watching", watching);
        stats.put("finished", finished);
        stats.put("planning", planning);
        stats.put("dropped", dropped);

        long totalEpisodes = allAnime.stream().mapToLong(Anime::getTotalEpisodes).sum();
        long watchedEpisodes = allAnime.stream().mapToLong(Anime::getCurrentEpisode).sum();
        double progressPercentage = totalEpisodes > 0 ? (watchedEpisodes * 100.0 / totalEpisodes) : 0;

        stats.put("totalEpisodes", totalEpisodes);
        stats.put("watchedEpisodes", watchedEpisodes);
        stats.put("progressPercentage", Math.round(progressPercentage * 10.0) / 10.0);

        double avgScore = allAnime.stream()
                .filter(a -> a.getScore() != null && a.getScore() > 0)
                .mapToDouble(Anime::getScore)
                .average()
                .orElse(0.0);
        stats.put("averageScore", Math.round(avgScore * 10.0) / 10.0);

        long highScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() >= 8.0).count();
        long mediumScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() >= 6.0 && a.getScore() < 8.0).count();
        long lowScore = allAnime.stream().filter(a -> a.getScore() != null && a.getScore() > 0 && a.getScore() < 6.0).count();

        stats.put("highScore", highScore);
        stats.put("mediumScore", mediumScore);
        stats.put("lowScore", lowScore);

        return stats;
    }

    @Override
    public List<AnimeVO> findAll() {
        return animeRepository.findAll().stream().map(this::toVO).collect(Collectors.toList());
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
        if (anime.getTotalEpisodes() != null && anime.getTotalEpisodes() > 0) {
            vo.setProgress(Math.round((double) anime.getCurrentEpisode() / anime.getTotalEpisodes() * 1000.0) / 10.0);
        }
        return vo;
    }
}
