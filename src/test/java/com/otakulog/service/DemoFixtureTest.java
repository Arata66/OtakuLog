package com.otakulog.service;

import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demo_fixture;DB_CLOSE_DELAY=-1")
class DemoFixtureTest {
    @Autowired private BackupService backup;
    @Autowired private AnimeRepository anime;
    @Autowired private EpisodeRecordRepository episodes;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void 清理隔离数据() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS anime_group_relation (group_id BIGINT NOT NULL, anime_id BIGINT NOT NULL, PRIMARY KEY(group_id,anime_id))");
        jdbc.update("DELETE FROM anime_group_relation");
        episodes.deleteAll(); anime.deleteAll();
        jdbc.update("DELETE FROM anime_group"); jdbc.update("DELETE FROM tag");
    }

    @Test
    void 当预览演示样例时应该有效且不写入() throws Exception {
        var result = backup.previewJson(fixture());
        assertEquals(true, result.get("valid")); assertEquals(4, result.get("created"));
        assertEquals(7, result.get("newEpisodes")); assertEquals(0, anime.count());
    }

    @Test
    void 当重复恢复演示样例时应该保留来源与分组且不重复() throws Exception {
        backup.importJson(fixture()); backup.importJson(fixture());
        assertEquals(4, anime.count()); assertEquals(7, episodes.count());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM anime_group_relation", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM episode_record WHERE record_source='LEGACY'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM episode_record WHERE watched_date IS NULL", Integer.class));
    }

    @Test
    void 当导入演示样例时应该包含可完成作品且不引用外部服务() throws Exception {
        backup.importJson(fixture());
        var item = anime.findAll().stream().filter(a -> a.getName().equals("演示·星港巡游")).findFirst().orElseThrow();
        assertEquals(2, item.getCurrentEpisode()); assertEquals(3, item.getTotalEpisodes());
        assertTrue(anime.findAll().stream().allMatch(a -> a.getBangumiId() == null && a.getCoverUrl() == null));
    }

    private String fixture() throws Exception {
        var file = new ClassPathResource("fixtures/demo-backup.json");
        assertTrue(file.exists(), "缺少虚构演示样例");
        return file.getContentAsString(StandardCharsets.UTF_8);
    }
}
