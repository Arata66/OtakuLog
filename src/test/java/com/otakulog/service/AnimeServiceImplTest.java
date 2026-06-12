package com.otakulog.service;

import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BangumiResult;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnimeServiceImplTest {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private AnimeRepository animeRepository;

    @Autowired
    private EpisodeRecordRepository episodeRecordRepository;

    @MockBean
    private BangumiService bangumiService;

    @BeforeEach
    void setUp() {
        episodeRecordRepository.deleteAll();
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

    private AnimeDTO createDetailedDTO(
            String name,
            int total,
            String season,
            double score,
            String tags,
            Integer bangumiId,
            String coverUrl,
            boolean legacy,
            String watchStartDate,
            String status) {
        AnimeDTO dto = new AnimeDTO();
        dto.setName(name);
        dto.setTotalEpisodes(total);
        dto.setSeason(season);
        dto.setScore(score);
        dto.setRemark("test");
        dto.setTags(tags);
        dto.setBangumiId(bangumiId);
        dto.setCoverUrl(coverUrl);
        dto.setLegacy(legacy);
        dto.setWatchStartDate(watchStartDate);
        dto.setStatus(status);
        return dto;
    }

    private BangumiResult createBangumiResult(int id, String name, String nameCn, String image, double score) {
        BangumiResult result = new BangumiResult();
        result.setId(id);
        result.setName(name);
        result.setNameCn(nameCn);
        result.setImage(image);
        result.setScore(score);
        return result;
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
        AnimeVO vo = animeService.addAnime(createDTO("测试番剧", 12, "2024冬", 8.0));

        AnimeVO updated = animeService.nextEpisode(vo.getId());
        assertEquals(2, updated.getCurrentEpisode());
        assertEquals("watching", updated.getStatus());
    }

    @Test
    void nextEpisode_autoFinish() {
        AnimeVO vo = animeService.addAnime(createDTO("短番", 2, "2024冬", 7.0));

        animeService.nextEpisode(vo.getId());

        assertThrows(IllegalArgumentException.class, () -> animeService.nextEpisode(vo.getId()));
    }

    @Test
    void prevEpisode() {
        AnimeVO vo = animeService.addAnime(createDTO("测试番剧", 12, "2024冬", 8.0));
        animeService.nextEpisode(vo.getId());
        animeService.nextEpisode(vo.getId());

        AnimeVO prev = animeService.prevEpisode(vo.getId());
        assertEquals(2, prev.getCurrentEpisode());
    }

    @Test
    void prevEpisode_reachedMin() {
        AnimeVO vo = animeService.addAnime(createDTO("测试番剧", 12, "2024冬", 8.0));
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
        AnimeVO vo = animeService.addAnime(createDTO("要删的番", 12, "2024冬", 7.0));
        animeService.deleteAnime(vo.getId());
        assertEquals(0, animeRepository.count());
    }

    @Test
    void deleteAnime_notFound() {
        assertThrows(ResourceNotFoundException.class, () -> animeService.deleteAnime(999L));
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
        AnimeVO v1 = animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 12, "2024冬", 7.0));
        animeService.nextEpisode(v1.getId());

        List<AnimeVO> watching = animeService.searchAnime(null, AnimeStatus.WATCHING, "id-desc", null);
        assertTrue(watching.size() >= 1);
    }

    @Test
    void getStats() {
        animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 24, "2024春", 9.0));

        Map<String, Object> stats = animeService.getStats();
        assertEquals(2L, stats.get("total"));
        assertEquals(2L, stats.get("watching"));
    }

    @Test
    void getDetailedStats() {
        animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 24, "2024春", 9.0));

        Map<String, Object> stats = animeService.getDetailedStats();
        assertEquals(2L, stats.get("total"));
        assertEquals(36L, stats.get("totalEpisodes"));
        assertNotNull(stats.get("averageScore"));
    }

    @Test
    void exportAndImportJson() {
        animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 24, "2024春", 9.0));

        String json = animeService.exportJson();
        assertNotNull(json);
        assertTrue(json.contains("番剧"));

        animeRepository.deleteAll();
        Map<String, Object> result = animeService.importJson(json);
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("updated"));
    }

    @Test
    void getSeasonStats() {
        animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 24, "2024冬", 9.0));
        animeService.addAnime(createDTO("番剧C", 12, "2024春", 7.0));

        Map<String, Object> result = animeService.getSeasonStats();
        List<?> seasons = (List<?>) result.get("seasons");
        assertNotNull(seasons);
        assertTrue(seasons.size() >= 2);
    }

    @Test
    void getTimeline() {
        animeService.addAnime(createDTO("番剧A", 12, "2024冬", 8.0));
        animeService.addAnime(createDTO("番剧B", 24, "2024春", 9.0));

        List<AnimeVO> timeline = animeService.getTimeline("watch");
        assertEquals(2, timeline.size());
    }

    @Test
    void searchAnimePaged() {
        for (int i = 1; i <= 15; i++) {
            animeService.addAnime(createDTO("番剧" + i, 12, "2024冬", 8.0));
        }

        var page = animeService.searchAnimePaged(null, null,
                org.springframework.data.domain.PageRequest.of(0, 10), null);
        assertEquals(10, page.getContent().size());
        assertEquals(15, page.getTotalElements());
    }

    @Test
    void 当番剧已有BangumiId时应该直接返回当前数据() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("已有补链", 12, "2024冬", 8.0, "校园", 1234, null, false, "2024-01-01", null));

        AnimeVO matched = animeService.matchBangumi(saved.getId());

        assertEquals(1234, matched.getBangumiId());
    }

    @Test
    void 当搜索结果存在精确匹配时应该写入BangumiId并补齐封面() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("孤独摇滚", 12, "2022秋", 9.0, "音乐", null, null, false, "2022-10-01", null));

        when(bangumiService.search(eq("孤独摇滚"), eq(5))).thenReturn(List.of(
                createBangumiResult(999, "孤独摇滚", "孤独摇滚", "https://img.test/bocchi.jpg", 8.8)
        ));

        AnimeVO matched = animeService.matchBangumi(saved.getId());

        assertEquals(999, matched.getBangumiId());
        assertEquals("https://img.test/bocchi.jpg", matched.getCoverUrl());
    }

    @Test
    void 当搜索结果没有精确匹配时应该抛出异常() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("未匹配番剧", 12, "2024春", 7.0, "悬疑", null, null, false, "2024-04-01", null));

        when(bangumiService.search(eq("未匹配番剧"), eq(5))).thenReturn(List.of(
                createBangumiResult(1001, "别的作品", "别的作品", null, 7.2)
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> animeService.matchBangumi(saved.getId()));

        assertEquals("未在 Bangumi 找到匹配结果", ex.getMessage());
    }

    @Test
    void 当批量补链部分成功部分失败时应该返回准确统计() {
        animeService.addAnime(createDetailedDTO("命运石之门", 24, "2011春", 9.5, "科幻", null, null, false, "2024-01-01", null));
        animeService.addAnime(createDetailedDTO("匹配失败作品", 12, "2024冬", 6.5, "悬疑", null, null, false, "2024-02-01", null));
        animeService.addAnime(createDetailedDTO("已有链接作品", 12, "2024冬", 8.0, "日常", 4567, null, false, "2024-03-01", null));

        when(bangumiService.search(eq("命运石之门"), eq(5))).thenReturn(List.of(
                createBangumiResult(2001, "命运石之门", "命运石之门", null, 9.1)
        ));
        when(bangumiService.search(eq("匹配失败作品"), eq(5))).thenReturn(List.of());

        Map<String, Object> result = animeService.batchMatchBangumi();

        assertEquals(1, result.get("matched"));
        assertEquals(1, result.get("failed"));
        assertEquals(2, result.get("total"));
    }

    @Test
    void 当生成推荐时应该过滤已追番和重复BangumiId() {
        animeService.addAnime(createDetailedDTO("葬送的芙莉莲", 28, "2023秋", 9.5, "奇幻,冒险", 3001, null, false, "2024-01-01", null));
        animeService.addAnime(createDetailedDTO("迷宫饭", 24, "2024冬", 8.8, "奇幻,美食", null, null, false, "2024-02-01", null));

        when(bangumiService.searchByTag(eq("奇幻"), anyInt())).thenReturn(List.of(
                createBangumiResult(3001, "葬送的芙莉莲", "葬送的芙莉莲", null, 9.5),
                createBangumiResult(4001, "奇幻新作", "奇幻新作", "https://img.test/fantasy.jpg", 8.0)
        ));
        when(bangumiService.searchByTag(eq("冒险"), anyInt())).thenReturn(List.of(
                createBangumiResult(4001, "奇幻新作", "奇幻新作", "https://img.test/fantasy.jpg", 8.0),
                createBangumiResult(4002, "冒险物语", "冒险物语", "https://img.test/adventure.jpg", 7.8)
        ));
        when(bangumiService.searchByTag(eq("美食"), anyInt())).thenReturn(List.of(
                createBangumiResult(4003, "美食之旅", "美食之旅", "https://img.test/food.jpg", 7.5)
        ));

        List<Map<String, Object>> recommendations = animeService.getRecommendations();

        assertFalse(recommendations.isEmpty());
        assertTrue(recommendations.stream().noneMatch(item -> Integer.valueOf(3001).equals(item.get("id"))));
        assertEquals(recommendations.size(),
                recommendations.stream().map(item -> item.get("id").toString()).distinct().count());
        assertTrue(recommendations.stream().allMatch(item -> item.containsKey("reason")));
    }

    @Test
    void 当存在观看记录时热力图应该优先使用真实记录() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("热力图样本", 12, "2024冬", 8.0, "日常", null, null, false, "2024-01-01", "planning"));

        EpisodeRecord record = new EpisodeRecord();
        record.setAnimeId(saved.getId());
        record.setEpisodeNumber(1);
        record.setWatchedDate(LocalDate.now());
        episodeRecordRepository.save(record);

        Map<String, Integer> heatmap = animeService.getHeatmap();

        assertTrue(heatmap.containsKey(LocalDate.now().toString()));
        assertEquals(1, heatmap.get(LocalDate.now().toString()));
    }

    @Test
    void 当没有观看记录时热力图应该回退到旧估算逻辑() {
        animeService.addAnime(createDetailedDTO(
                "旧逻辑热力图", 12, "2024冬", 8.2, "校园", null, null, false, LocalDate.now().minusDays(2).toString(), null));
        episodeRecordRepository.deleteAll();

        Map<String, Integer> heatmap = animeService.getHeatmap();

        assertTrue(heatmap.containsKey(LocalDate.now().toString()));
        assertTrue(heatmap.get(LocalDate.now().toString()) >= 1);
    }
}
