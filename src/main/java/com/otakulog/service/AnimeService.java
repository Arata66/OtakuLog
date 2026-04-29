package com.otakulog.service;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.enums.AnimeStatus;

import java.util.List;
import java.util.Map;

public interface AnimeService {
    AnimeVO addAnime(AnimeDTO dto);

    AnimeVO nextEpisode(Long id);

    AnimeVO prevEpisode(Long id);

    AnimeVO updateAnime(Long id, AnimeUpdateDTO dto);

    AnimeVO updateStatus(Long id, AnimeStatus status);

    void deleteAnime(Long id);

    List<AnimeVO> searchAnime(String name, AnimeStatus status, String sortBy);

    Map<String, Object> getStats();

    Map<String, Object> getDetailedStats();

    List<AnimeVO> findAll();
}
