package com.otakulog.service;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnimeServiceImplTest {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private AnimeRepository animeRepository;

    @BeforeEach
    void setUp() {
        animeRepository.deleteAll();
    }

    private AnimeDTO createDTO(String name, int total, String season, double score) {
        AnimeDTO dto = new AnimeDTO();
        dto.setName(name);
        dto.setTotalEpisodes(total);
        dto.setSeason(season);
        dto.setScore(score);
        dto.setRemark("test");
        return dto;
    }

    @Test
    void addAnime() {
        AnimeDTO dto = createDTO("葬送的芙莉莲", 28, "2023秋", 9.0);
        AnimeVO vo = animeService.addAnime(dto);

        assertNotNull(vo.getId());
        assertEquals("葬送的芙莉莲", vo.getName());
        assertEquals(1, vo.getCurrentEpisode());
        assertEquals(28, vo.getTotalEpisodes());
        assertEquals("watching", vo.getStatus());
        assertEquals(9.0, vo.getScore());
    }

    @Test
    void nextEpisode() {
        AnimeVO vo = animeService.addAnime(createDTO("测试番", 12, "2024冬", 8.0));

        AnimeVO updated = animeService.nextEpisode(vo.getId());
        assertEquals(2, updated.getCurrentEpisode());
        assertEquals("watching", updated.getStatus());
    }

    @Test
    void nextEpisode_autoFinish() {
        AnimeVO vo = animeService.addAnime(createDTO("短番", 2, "2024冬", 7.0));

        animeService.nextEpisode(vo.getId()); // ep 2 = total

        // Already at max, should throw
        assertThrows(IllegalArgumentException.class, () -> animeService.nextEpisode(vo.getId()));
    }

    @Test
    void prevEpisode() {
        AnimeVO vo = animeService.addAnime(createDTO("测试番", 12, "2024冬", 8.0));
        animeService.nextEpisode(vo.getId());
        animeService.nextEpisode(vo.getId());

        AnimeVO prev = animeService.prevEpisode(vo.getId());
        assertEquals(2, prev.getCurrentEpisode());
    }

    @Test
    void prevEpisode_reachedMin() {
        AnimeVO vo = animeService.addAnime(createDTO("测试番", 12, "2024冬", 8.0));
        assertThrows(IllegalArgumentException.class, () -> animeService.prevEpisode(vo.getId()));
    }

    @Test
    void updateAnime() {
        AnimeVO vo = animeService.addAnime(createDTO("原名", 12, "2024冬", 7.0));

        AnimeUpdateDTO dto = new AnimeUpdateDTO();
        dto.setName("新名");
        dto.setSeason("2024春");
        dto.setScore(8.5);
        dto.setRemark("updated");

        AnimeVO updated = animeService.updateAnime(vo.getId(), dto);
        assertEquals("新名", updated.getName());
        assertEquals("2024春", updated.getSeason());
        assertEquals(8.5, updated.getScore());
    }

    @Test
    void deleteAnime() {
        AnimeVO vo = animeService.addAnime(createDTO("要删的", 12, "2024冬", 7.0));
        animeService.deleteAnime(vo.getId());
        assertEquals(0, animeRepository.count());
    }

    @Test
    void deleteAnime_notFound() {
        assertThrows(IllegalArgumentException.class, () -> animeService.deleteAnime(999L));
    }

    @Test
    void searchAnime() {
        animeService.addAnime(createDTO("葬送的芙莉莲", 28, "2023秋", 9.0));
        animeService.addAnime(createDTO("进击的巨人", 87, "2013春", 9.5));
        animeService.addAnime(createDTO("间谍过家家", 25, "2022春", 8.0));

        List<AnimeVO> results = animeService.searchAnime("芙莉莲", null, "id-desc", null);
        assertEquals(1, results.size());
        assertEquals("葬送的芙莉莲", results.get(0).getName());
    }

    @Test
    void searchAnime_byStatus() {
        AnimeVO v1 = animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 12, "2024冬", 7.0));
        animeService.nextEpisode(v1.getId());

        List<AnimeVO> watching = animeService.searchAnime(null, AnimeStatus.WATCHING, "id-desc", null);
        assertTrue(watching.size() >= 1);
    }

    @Test
    void getStats() {
        animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 24, "2024春", 9.0));

        Map<String, Object> stats = animeService.getStats();
        assertEquals(2L, stats.get("total"));
        assertEquals(2L, stats.get("watching"));
    }

    @Test
    void getDetailedStats() {
        animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 24, "2024春", 9.0));

        Map<String, Object> stats = animeService.getDetailedStats();
        assertEquals(2L, stats.get("total"));
        assertEquals(36L, stats.get("totalEpisodes"));
        assertNotNull(stats.get("averageScore"));
    }

    @Test
    void exportAndImportJson() {
        animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 24, "2024春", 9.0));

        String json = animeService.exportJson();
        assertNotNull(json);
        assertTrue(json.contains("番1"));

        animeRepository.deleteAll();
        Map<String, Object> result = animeService.importJson(json);
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("updated"));
    }

    @Test
    void getSeasonStats() {
        animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 24, "2024冬", 9.0));
        animeService.addAnime(createDTO("番3", 12, "2024春", 7.0));

        Map<String, Object> result = animeService.getSeasonStats();
        List<?> seasons = (List<?>) result.get("seasons");
        assertNotNull(seasons);
        assertTrue(seasons.size() >= 2);
    }

    @Test
    void getTimeline() {
        animeService.addAnime(createDTO("番1", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番2", 24, "2024春", 9.0));

        List<AnimeVO> timeline = animeService.getTimeline();
        assertEquals(2, timeline.size());
    }

    @Test
    void searchAnimePaged() {
        for (int i = 1; i <= 15; i++) {
            animeService.addAnime(createDTO("番" + i, 12, "2024冬", 8.0));
        }

        var page = animeService.searchAnimePaged(null, null,
                org.springframework.data.domain.PageRequest.of(0, 10), null);
        assertEquals(10, page.getContent().size());
        assertEquals(15, page.getTotalElements());
    }
}
