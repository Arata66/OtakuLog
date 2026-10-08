package com.otakulog.service;

import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:watch_consistency;DB_CLOSE_DELAY=-1")
class WatchConsistencyTest {

    @Autowired private AnimeService service;
    @Autowired private TagRepository tags;
    @SpyBean private AnimeRepository anime;
    @SpyBean private EpisodeRecordRepository records;
    @MockBean private BangumiService bangumi;

    @BeforeEach
    void 清理隔离测试数据() {
        records.deleteAll();
        anime.deleteAll();
        tags.deleteAll();
    }

    @Test
    void 当新增观看记录失败时应该回滚番剧和标签() {
        AnimeDTO dto = dto("新增回滚", "watching");
        dto.setTags("不能残留");
        doThrow(new IllegalStateException("模拟记录写入失败")).when(records).save(any(EpisodeRecord.class));
        assertThrows(RuntimeException.class, () -> service.addAnime(dto));
        assertEquals(0, anime.count());
        assertEquals(0, tags.count());
    }

    @Test
    void 当下一集保存番剧失败时应该回滚新增记录() {
        Long id = service.addAnime(dto("进集回滚", "watching")).getId();
        doThrow(new IllegalStateException("模拟番剧保存失败")).when(anime).save(any(Anime.class));
        assertThrows(RuntimeException.class, () -> service.nextEpisode(id));
        assertEquals(1, anime.findById(id).orElseThrow().getCurrentEpisode());
        assertFalse(records.findByAnimeIdAndEpisodeNumber(id, 2).isPresent());
    }

    @Test
    void 当上一集保存番剧失败时应该恢复删除的记录() {
        Long id = service.addAnime(dto("退集回滚", "watching")).getId();
        service.nextEpisode(id);
        doThrow(new IllegalStateException("模拟番剧保存失败")).when(anime).save(any(Anime.class));
        assertThrows(RuntimeException.class, () -> service.prevEpisode(id));
        assertEquals(2, anime.findById(id).orElseThrow().getCurrentEpisode());
        assertTrue(records.findByAnimeIdAndEpisodeNumber(id, 2).isPresent());
    }

    @Test
    void 当退回第一集之前时应该回到计划并删除该集记录() {
        Long id = service.addAnime(dto("退至计划", "watching")).getId();
        AnimeVO result = service.prevEpisode(id);
        assertEquals(0, result.getCurrentEpisode());
        assertEquals("planning", result.getStatus());
        assertNull(result.getEndDate());
        assertFalse(records.existsByAnimeId(id));
        assertThrows(IllegalArgumentException.class, () -> service.prevEpisode(id));
    }

    @Test
    void 当单集作品开始观看时应该自动完成() {
        AnimeDTO dto = dto("单集作品", "watching");
        dto.setTotalEpisodes(1);
        AnimeVO result = service.addAnime(dto);
        assertEquals("finished", result.getStatus());
        assertEquals(LocalDate.now().toString(), result.getEndDate());
    }

    @Test
    void 当手动标记完成时应该补齐进度但不伪造逐集日期() {
        Long id = service.addAnime(dto("手动完成", "watching")).getId();
        LocalDate original = LocalDate.of(2025, 12, 31);
        EpisodeRecord first = records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow();
        first.setWatchedDate(original);
        records.save(first);
        AnimeVO result = service.updateStatus(id, AnimeStatus.FINISHED);
        assertEquals(3, result.getCurrentEpisode());
        assertEquals(original, records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getWatchedDate());
        assertNull(records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getWatchedDate());
        assertEquals(EpisodeRecordSource.MANUAL, records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getSource());
    }

    @Test
    void 当批量开始观看时应该与单条操作拥有相同进度和记录() {
        Long one = service.addAnime(dto("单条开始", "planning")).getId();
        Long batch = service.addAnime(dto("批量开始", "planning")).getId();
        service.updateStatus(one, AnimeStatus.WATCHING);
        service.batchUpdateStatus(List.of(batch), AnimeStatus.WATCHING);
        assertEquals(anime.findById(one).orElseThrow().getCurrentEpisode(),
                anime.findById(batch).orElseThrow().getCurrentEpisode());
        assertTrue(records.findByAnimeIdAndEpisodeNumber(batch, 1).isPresent());
    }

    @Test
    void 当批量完成包含不存在的番剧时应该整批拒绝() {
        Long id = service.addAnime(dto("批量回滚", "planning")).getId();
        assertThrows(RuntimeException.class, () -> service.batchUpdateStatus(List.of(id, Long.MAX_VALUE), AnimeStatus.FINISHED));
        assertEquals(AnimeStatus.PLANNING, anime.findById(id).orElseThrow().getStatus());
        assertFalse(records.existsByAnimeId(id));
    }

    @Test
    void 当重复标记完成时应该保留历史完成日期() {
        AnimeDTO dto = dto("历史完成", "finished");
        dto.setEndDate("2024-01-02");
        Long id = service.addAnime(dto).getId();
        assertEquals("2024-01-02", service.updateStatus(id, AnimeStatus.FINISHED).getEndDate());
        assertNull(service.updateStatus(id, AnimeStatus.DROPPED).getEndDate());
    }

    @Test
    void 当已有观看进度时应该拒绝直接改为计划以保留历史() {
        Long id = service.addAnime(dto("保留历史", "watching")).getId();
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(id, AnimeStatus.PLANNING));
        assertTrue(records.existsByAnimeId(id));
    }

    @Test
    void 当新增时填写观看日期时应该保留该日期() {
        AnimeDTO dto = dto("历史首集", "watching");
        dto.setWatchStartDate("2024-02-03");
        Long id = service.addAnime(dto).getId();
        assertEquals(LocalDate.of(2024, 2, 3), records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getWatchedDate());
    }

    @Test
    void 当旧格式导入已有番剧时应该保留逐集观看日期() {
        Long id = service.addAnime(dto("合并历史", "watching")).getId();
        EpisodeRecord first = records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow();
        first.setWatchedDate(LocalDate.of(2024, 2, 3));
        records.save(first);
        service.importJson(json("合并历史", 2, "watching"));
        assertEquals(LocalDate.of(2024, 2, 3), records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getWatchedDate());
        assertNull(records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getWatchedDate());
        assertEquals(EpisodeRecordSource.IMPORT, records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getSource());
    }

    @Test
    void 当导入只有进度没有逐集日期时应该不产生热力图观看量() {
        service.importJson(json("未知日期", 2, "watching"));
        assertTrue(service.getHeatmap().values().stream().allMatch(count -> count == 0));
    }

    @Test
    void 当导入第二条非法时应该回滚第一条和新标签() {
        String json = "[{\"name\":\"不能残留\",\"totalEpisodes\":3,\"currentEpisode\":1,\"tags\":\"新标签\"},"
                + "{\"name\":\"错误状态\",\"totalEpisodes\":3,\"status\":\"bad\"}]";
        assertThrows(RuntimeException.class, () -> service.importJson(json));
        assertEquals(0, anime.count());
        assertEquals(0, tags.count());
        assertEquals(0, records.count());
    }

    @Test
    void 当导入包含重复名称时应该复用同一番剧() {
        String row = json("重复导入", 1, "watching");
        service.importJson("[" + row.substring(1, row.length() - 1) + "," + row.substring(1, row.length() - 1) + "]");
        assertEquals(1, anime.count());
        assertEquals(1, records.count());
    }

    @Test
    void 当Bangumi导入没有逐集日期时应该只补未知日期记录() {
        when(bangumi.getUserCollections("test", 200)).thenReturn(List.of(Map.of(
                "name", "远程导入", "subjectId", 123, "eps", 3, "epStatus", 2, "type", 3)));
        service.importFromBangumi("test");
        assertEquals(2, records.count());
        assertTrue(records.findAll().stream().allMatch(record -> record.getWatchedDate() == null));
        assertTrue(service.getHeatmap().values().stream().allMatch(count -> count == 0));
    }

    private AnimeDTO dto(String name, String status) {
        AnimeDTO dto = new AnimeDTO();
        dto.setName(name);
        dto.setTotalEpisodes(3);
        dto.setSeason("2026秋");
        dto.setScore(0.0);
        dto.setStatus(status);
        return dto;
    }

    @Test
    void 当增加已完成作品总集数时应该恢复追中并清除完成日期() {
        Long id = service.addAnime(dto("增加总集数", "finished")).getId();
        AnimeUpdateDTO update = update("增加总集数", 5);
        AnimeVO result = service.updateAnime(id, update);
        assertEquals(3, result.getCurrentEpisode());
        assertEquals("watching", result.getStatus());
        assertNull(result.getEndDate());
    }

    @Test
    void 当编辑总集数低于进度时应该拒绝并保留原值() {
        Long id = service.addAnime(dto("不能缩减", "finished")).getId();
        assertThrows(IllegalArgumentException.class, () -> service.updateAnime(id, update("不能缩减", 2)));
        assertEquals(3, anime.findById(id).orElseThrow().getTotalEpisodes());
    }

    @Test
    void 当旧格式导入进度倒退时应该拒绝并保留已有日期() {
        Long id = service.addAnime(dto("防止倒退", "watching")).getId();
        service.nextEpisode(id);
        LocalDate date = records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getWatchedDate();
        assertThrows(IllegalArgumentException.class, () -> service.importJson(json("防止倒退", 1, "watching")));
        assertEquals(2, anime.findById(id).orElseThrow().getCurrentEpisode());
        assertEquals(date, records.findByAnimeIdAndEpisodeNumber(id, 2).orElseThrow().getWatchedDate());
    }

    @Test
    void 当批量完成时应该同步进度并只补未知日期() {
        Long id = service.addAnime(dto("批量完成", "planning")).getId();
        service.batchUpdateStatus(List.of(id), AnimeStatus.FINISHED);
        assertEquals(3, anime.findById(id).orElseThrow().getCurrentEpisode());
        assertEquals(3, records.count());
        assertTrue(records.findAll().stream().allMatch(record -> record.getWatchedDate() == null));
    }

    @Test
    void 当完成后退集时应该恢复追中并清除完成日期() {
        Long id = service.addAnime(dto("完成退集", "watching")).getId();
        service.nextEpisode(id);
        assertEquals("finished", service.nextEpisode(id).getStatus());
        AnimeVO result = service.prevEpisode(id);
        assertEquals("watching", result.getStatus());
        assertNull(result.getEndDate());
        assertFalse(records.findByAnimeIdAndEpisodeNumber(id, 3).isPresent());
    }

    @Test
    void 当已有记录都在统计窗口外时应该不回退伪造观看量() {
        Long id = service.addAnime(dto("窗口外记录", "watching")).getId();
        EpisodeRecord record = records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow();
        record.setWatchedDate(LocalDate.now().minusYears(2));
        records.save(record);
        assertTrue(service.getHeatmap().values().stream().allMatch(count -> count == 0));
    }

    @Test
    void 当旧番没有提供日期时应该保留未知日期() {
        AnimeDTO dto = dto("未知旧番", "finished");
        dto.setLegacy(true);
        AnimeVO result = service.addAnime(dto);
        assertNull(result.getEndDate());
        assertNull(result.getWatchStartDate());
        assertTrue(records.findAll().stream().allMatch(record -> record.getWatchedDate() == null));
    }

    @Test
    void 当主动填写非法日期时应该拒绝而非补成今天() {
        AnimeDTO dto = dto("非法日期", "watching");
        dto.setWatchStartDate("日期未知");
        assertThrows(IllegalArgumentException.class, () -> service.addAnime(dto));
        assertEquals(0, anime.count());
    }

    private AnimeUpdateDTO update(String name, int total) {
        AnimeUpdateDTO dto = new AnimeUpdateDTO();
        dto.setName(name);
        dto.setTotalEpisodes(total);
        dto.setSeason("2026秋");
        dto.setScore(0.0);
        return dto;
    }

    @Test
    void 当编辑状态失败时应该回滚同表单的名称和标签修改() {
        Long id = service.addAnime(dto("原资料", "watching")).getId();
        AnimeUpdateDTO update = update("不能残留的新名", 3);
        update.setTags("不能残留的标签");
        update.setStatus("planning");
        assertThrows(IllegalArgumentException.class, () -> service.updateAnime(id, update));
        assertEquals("原资料", anime.findById(id).orElseThrow().getName());
        assertEquals(0, tags.count());
    }

    @Test
    void 当编辑资料并标记完成时应该一起写入并保留填写的完成日期() {
        Long id = service.addAnime(dto("编辑完成", "watching")).getId();
        AnimeUpdateDTO update = update("编辑完成新名", 3);
        update.setStatus("finished");
        update.setEndDate("2025-12-31");
        AnimeVO result = service.updateAnime(id, update);
        assertEquals("finished", result.getStatus());
        assertEquals(3, result.getCurrentEpisode());
        assertEquals("2025-12-31", result.getEndDate());
    }

    @Test
    void 当已完成的导入作品日期未知时重复标记应该仍保留未知日期() {
        service.importJson(json("未知完成日期", 3, "finished"));
        Long id = anime.findAll().get(0).getId();
        assertNull(service.updateStatus(id, AnimeStatus.FINISHED).getEndDate());
    }

    private String json(String name, int episode, String status) {
        return "[{\"name\":\"" + name + "\",\"totalEpisodes\":3,\"currentEpisode\":" + episode
                + ",\"status\":\"" + status + "\",\"watchStartDate\":\"" + LocalDate.now() + "\"}]";
    }
}
