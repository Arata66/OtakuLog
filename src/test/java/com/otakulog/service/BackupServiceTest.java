package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:backup;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class BackupServiceTest {
    @Autowired private AnimeService service;
    @Autowired private BackupService backup;
    @Autowired private AnimeRepository anime;
    @Autowired private TagRepository tags;
    @Autowired private AnimeGroupRepository groups;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @SpyBean private EpisodeRecordRepository episodes;
    @MockBean private BangumiService bangumi;

    public static final String BACKUP = """
        {"format":"otakulog-backup","version":1,"exportedAt":"2026-10-01T12:00:00",
         "anime":[{"key":"a:700","data":{"name":"完整恢复","currentEpisode":2,"totalEpisodes":12,
           "status":"WATCHING","score":8.5,"season":"2026秋","remark":"保留备注","coverUrl":"/cover.jpg",
           "startDate":"2026-10-01","endDate":null,"sortOrder":3,"broadcastDay":4,"bangumiId":100,
           "watchStartDate":"2026-10-02","legacy":false,"watchSeason":"2026秋",
           "createdAt":"2025-01-01T12:00:00","updatedAt":"2025-02-01T12:00:00"},"tagKeys":["t:900"]}],
         "tags":[{"key":"t:900","name":"剧情","createdAt":"2025-01-01T12:00:00"}],
         "groups":[{"key":"g:800","name":"收藏","description":"分组说明","color":"#123456","sortOrder":1,
           "createdAt":"2025-01-01T12:00:00","updatedAt":"2025-02-01T12:00:00"}],
         "memberships":[{"groupKey":"g:800","animeKey":"a:700"}],
         "episodes":[{"animeKey":"a:700","episodeNumber":1,"watchedDate":"2026-10-02","source":"WATCHED",
           "createdAt":"2025-01-01T12:00:00","updatedAt":"2025-02-01T12:00:00"},
           {"animeKey":"a:700","episodeNumber":2,"watchedDate":null,"source":"IMPORT",
           "createdAt":"2025-01-01T12:00:00","updatedAt":"2025-02-01T12:00:00"}]}
        """;

    @BeforeEach
    void 清理隔离测试数据() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS anime_group_relation (group_id BIGINT NOT NULL, anime_id BIGINT NOT NULL, PRIMARY KEY(group_id,anime_id))");
        jdbc.update("DELETE FROM anime_group_relation");
        episodes.deleteAll(); anime.deleteAll(); tags.deleteAll(); groups.deleteAll();
    }

    @Test
    void 当导出时应该包含版本及所有数据集合() throws Exception {
        var root = new ObjectMapper().readTree(service.exportJson());
        assertTrue(root.isObject());
        assertEquals(2, root.path("version").asInt());
        for (String collection : new String[]{"anime", "tags", "groups", "memberships", "episodes", "memories"})
            assertTrue(root.path(collection).isArray(), collection);
    }

    @Test
    void 当恢复完整备份时应该重映射编号并保留日期来源与关联() {
        assertEquals(1, service.importJson(BACKUP).get("created"));
        var restored = anime.findAll().get(0);
        assertNotEquals(700L, restored.getId());
        assertEquals("保留备注", restored.getRemark());
        assertEquals(8.5, restored.getScore());
        assertEquals(LocalDateTime.parse("2025-01-01T12:00:00"), restored.getCreatedAt());
        assertEquals(LocalDateTime.parse("2025-02-01T12:00:00"), restored.getUpdatedAt());
        assertEquals("剧情", restored.getTags().iterator().next().getName());
        assertEquals(1, groups.countAnimeByGroupId(groups.findAll().get(0).getId()));
        var first = episodes.findByAnimeIdAndEpisodeNumber(restored.getId(), 1).orElseThrow();
        assertEquals(LocalDate.parse("2026-10-02"), first.getWatchedDate());
        assertEquals("WATCHED", first.getSource().name());
        var second = episodes.findByAnimeIdAndEpisodeNumber(restored.getId(), 2).orElseThrow();
        assertNull(second.getWatchedDate());
        assertEquals("IMPORT", second.getSource().name());
    }

    @Test
    void 当重复恢复时应该不重复数据且保留审计时间() {
        service.importJson(BACKUP);
        var timestamp = anime.findAll().get(0).getUpdatedAt();
        service.importJson(BACKUP);
        assertEquals(1, anime.count()); assertEquals(1, tags.count()); assertEquals(1, groups.count());
        assertEquals(2, episodes.count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM anime_group_relation", Integer.class));
        assertEquals(timestamp, anime.findAll().get(0).getUpdatedAt());
    }

    @Test
    void 当记录写入中途失败时应该整批回滚所有数据() {
        doThrow(new IllegalStateException("模拟恢复失败")).when(episodes).save(any(EpisodeRecord.class));
        assertThrows(RuntimeException.class, () -> service.importJson(BACKUP));
        assertEquals(0, anime.count()); assertEquals(0, tags.count()); assertEquals(0, groups.count());
        assertEquals(0, episodes.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM anime_group_relation", Integer.class));
    }

    @Test
    void 当备份存在无效结构时应该拒绝且没有残留写入() {
        for (String invalid : new String[]{BACKUP.replace("\"version\":1", "\"version\":2"),
                BACKUP.replace("\"groupKey\":\"g:800\"", "\"groupKey\":\"missing\""),
                BACKUP.replace("\"WATCHED\"", "\"UNKNOWN\""), BACKUP.replace("2026-10-02", "2026-99-99")}) {
            assertThrows(RuntimeException.class, () -> service.importJson(invalid));
            assertEquals(0, anime.count()); assertEquals(0, tags.count()); assertEquals(0, groups.count());
        }
    }

    @Test
    void 当预览有效备份时应该展示数量且不写数据库() {
        var preview = backup.previewJson(BACKUP);
        assertEquals(true, preview.get("valid")); assertEquals(1, preview.get("created"));
        assertEquals(2, preview.get("newEpisodes")); assertEquals(1, preview.get("newGroups"));
        assertEquals(0, anime.count()); assertEquals(0, episodes.count()); assertEquals(0, tags.count());
    }

    @Test
    void 当名称与Bangumi编号冲突时应该预览拒绝并保留原数据() {
        service.importJson(BACKUP);
        String conflict = BACKUP.replace("\"bangumiId\":100", "\"bangumiId\":101");
        assertEquals(false, backup.previewJson(conflict).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> service.importJson(conflict));
        assertEquals(100, anime.findAll().get(0).getBangumiId()); assertEquals(2, episodes.count());
    }

    @Test
    void 当已有观看日期与备份冲突时应该拒绝且保留所有资料() {
        service.importJson(BACKUP);
        String conflict = BACKUP.replace("2026-10-02", "2026-10-03");
        assertEquals(false, backup.previewJson(conflict).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> service.importJson(conflict));
        assertEquals(LocalDate.parse("2026-10-02"), episodes.findAll().get(0).getWatchedDate());
    }

    @Test
    void 当相同观看日期的来源不同时应该拒绝() {
        service.importJson(BACKUP);
        assertThrows(IllegalArgumentException.class, () -> service.importJson(BACKUP.replace("\"WATCHED\"", "\"MANUAL\"")));
    }

    @Test
    void 当备份没有日期时应该保留本地已知日期() {
        service.importJson(BACKUP);
        service.importJson(BACKUP.replace("\"watchedDate\":\"2026-10-02\",\"source\":\"WATCHED\"", "\"watchedDate\":null,\"source\":\"IMPORT\""));
        assertEquals(LocalDate.parse("2026-10-02"), episodes.findAll().get(0).getWatchedDate());
    }

    @Test
    void 当本地日期未知时应该用备份日期补齐且同步来源() {
        service.importJson(BACKUP.replace("\"watchedDate\":\"2026-10-02\",\"source\":\"WATCHED\"", "\"watchedDate\":null,\"source\":\"IMPORT\""));
        assertEquals(1, backup.previewJson(BACKUP).get("filledDates"));
        service.importJson(BACKUP);
        assertEquals(LocalDate.parse("2026-10-02"), episodes.findAll().get(0).getWatchedDate());
        assertEquals("WATCHED", episodes.findAll().get(0).getSource().name());
    }

    @Test
    void 当合并已有作品时应该保留本地资料和更高进度() {
        service.importJson(BACKUP);
        var local = anime.findAll().get(0); local.setRemark("本地备注"); local.setCurrentEpisode(3); anime.save(local);
        service.importJson(BACKUP);
        assertEquals("本地备注", anime.findAll().get(0).getRemark());
        assertEquals(3, anime.findAll().get(0).getCurrentEpisode());
    }

    @Test
    void 当同名分组资料不同时应该拒绝而不覆盖() {
        service.importJson(BACKUP);
        assertEquals(false, backup.previewJson(BACKUP.replace("分组说明", "不同分组说明")).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> service.importJson(BACKUP.replace("分组说明", "不同分组说明")));
        assertEquals("分组说明", groups.findAll().get(0).getDescription());
    }

    @Test
    void 当导出后在空库恢复时应该保持全部备份内容() throws Exception {
        service.importJson(BACKUP);
        var mapper = new ObjectMapper();
        var before = mapper.readTree(service.exportJson());
        清理隔离测试数据();
        service.importJson(before.toString());
        var after = mapper.readTree(service.exportJson());
        // 文件内部编号可变化，业务资料、记录来源和审计时间必须一致。
        assertEquals(before.at("/anime/0/data"), after.at("/anime/0/data"));
        assertEquals(before.at("/episodes/0/watchedDate"), after.at("/episodes/0/watchedDate"));
        assertEquals(before.at("/episodes/1/source"), after.at("/episodes/1/source"));
        assertEquals(1, after.path("memberships").size());
    }

    @Test
    void 当备份缺字段或存在重复编号时应该预览拒绝() {
        for (String invalid : new String[]{BACKUP.replace("\"episodes\":", "\"unknown\":"),
                BACKUP.replace("\"tagKeys\":[\"t:900\"]", "\"tagKeys\":[\"t:900\",\"t:900\"]"),
                BACKUP.replace("\"episodeNumber\":2", "\"episodeNumber\":1"),
                BACKUP.replace("\"totalEpisodes\":12", "\"totalEpisodes\":1"),
                BACKUP.replace("\"score\":8.5", "\"score\":\"8.5\""),
                BACKUP.replace("\"watchSeason\":\"2026秋\"", "\"watchSeason\":\"" + "长".repeat(21) + "\"")}) {
            assertEquals(false, backup.previewJson(invalid).get("valid"));
            assertThrows(IllegalArgumentException.class, () -> service.importJson(invalid));
            assertEquals(0, anime.count());
        }
    }

    @Test
    void 当经过认证预览导入时应该返回冲突和数量且不写入() throws Exception {
        mvc.perform(post("/api/anime/import/preview").with(user("test")).with(csrf())
                        .contentType("application/json").content(BACKUP))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.created").value(1));
        assertEquals(0, anime.count());
        mvc.perform(post("/api/anime/import/preview").with(user("test"))
                        .contentType("application/json").content(BACKUP)).andExpect(status().isForbidden());
    }

    @Test
    void 当历史完成状态与进度不一致时应该原样恢复() {
        service.importJson(BACKUP.replace("\"status\":\"WATCHING\"", "\"status\":\"FINISHED\""));
        assertEquals(2, anime.findAll().get(0).getCurrentEpisode());
        assertEquals(12, anime.findAll().get(0).getTotalEpisodes());
        assertNull(anime.findAll().get(0).getEndDate());
    }

    @Test
    void 当历史审计时间为空时应该保留未知而非补成恢复时间() throws Exception {
        var root = new ObjectMapper().readTree(BACKUP);
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) root.at("/anime/0/data");
        data.putNull("createdAt"); data.putNull("updatedAt");
        for (String collection : new String[]{"groups", "episodes"}) for (var entry : root.path(collection)) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) entry).putNull("createdAt").putNull("updatedAt");
        }
        service.importJson(root.toString());
        assertNull(anime.findAll().get(0).getCreatedAt()); assertNull(anime.findAll().get(0).getUpdatedAt());
        assertNull(groups.findAll().get(0).getUpdatedAt());
        assertNull(episodes.findAll().get(0).getCreatedAt());
    }

    @Test
    void 当完整格式缺失业务字段时应该拒绝而非按默认值恢复() {
        String missing = BACKUP.replace("\"legacy\":false,", "");
        assertEquals(false, backup.previewJson(missing).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> service.importJson(missing));
    }

    @Test
    void 当旧格式包含异常大进度时应该迅速拒绝且不写入() {
        assertTimeout(java.time.Duration.ofSeconds(5), () -> {
            for (String json : new String[]{"[{\"name\":\"错误进度\",\"totalEpisodes\":3,\"currentEpisode\":2147483647}]",
                    "[{\"name\":\"过大文件\",\"totalEpisodes\":2147483647,\"currentEpisode\":2147483647}]"})
                assertThrows(IllegalArgumentException.class, () -> service.importJson(json));
        });
        assertEquals(0, anime.count()); assertEquals(0, episodes.count());
    }
}
