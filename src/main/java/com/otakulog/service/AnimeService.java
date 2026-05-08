package com.otakulog.service;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.enums.AnimeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface AnimeService {
    AnimeVO addAnime(AnimeDTO dto);

    AnimeVO nextEpisode(Long id);

    AnimeVO prevEpisode(Long id);

    AnimeVO updateAnime(Long id, AnimeUpdateDTO dto);

    AnimeVO updateStatus(Long id, AnimeStatus status);

    void deleteAnime(Long id);

    void batchDelete(List<Long> ids);

    void batchUpdateStatus(List<Long> ids, AnimeStatus status);

    List<AnimeVO> searchAnime(String name, AnimeStatus status, String sortBy, String tag);

    Page<AnimeVO> searchAnimePaged(String name, AnimeStatus status, Pageable pageable, String tag);

    Map<String, Object> getStats();

    Map<String, Object> getDetailedStats();

    List<AnimeVO> findAll();

    Map<String, Object> getSeasonStats();

    Map<String, Object> getEnhancedStats();

    List<AnimeVO> getTimeline(String mode);

    void reorderAnime(List<Map<String, Object>> orders);

    String exportJson();

    Map<String, Object> importJson(String json);

    Map<Integer, List<AnimeVO>> getCalendarData();

    AnimeVO matchBangumi(Long id);

    Map<String, Object> batchMatchBangumi();

    Map<String, Object> importFromBangumi(String username);

    List<Map<String, Object>> getRecommendations();

    Map<String, Integer> getHeatmap();
}
