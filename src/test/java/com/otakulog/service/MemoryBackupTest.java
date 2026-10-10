package com.otakulog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.Anime;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:memory_backup;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class MemoryBackupTest {
    @Autowired private BackupService backup;
    @Autowired private AnimeRepository anime;
    @Autowired private EpisodeRecordRepository episodes;
    @Autowired private TagRepository tags;
    @Autowired private AnimeGroupRepository groups;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @SpyBean private AnimeMemoryRepository memories;

    @BeforeEach
    void 准备独立测试库() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS anime_group_relation (group_id BIGINT NOT NULL, anime_id BIGINT NOT NULL, PRIMARY KEY(group_id,anime_id))");
        jdbc.update("DELETE FROM anime_group_relation");
        memories.deleteAll(); episodes.deleteAll(); anime.deleteAll(); tags.deleteAll(); groups.deleteAll();
    }

    @Test
    void 当导出完整备份时应该使用版本二并包含观影记忆集合() throws Exception {
        var document = mapper.readTree(backup.exportJson());
        assertEquals(2, document.path("version").asInt());
        assertTrue(document.path("memories").isArray());
    }

    @Test
    void 当恢复记忆时应该重映射作品且保留原文未知日期版本和时间() throws Exception {
        documentWithMemory();
        var record = memories.findAll().get(0);
        mvc.perform(put("/api/anime/" + record.getAnimeId() + "/memories/" + record.getId()).with(csrf())
                .contentType("application/json").content("{\"content\":\"回望后的感想\",\"liked\":\"人物关系\",\"disliked\":\"部分情节\",\"scope\":\"电影\",\"context\":\"REFLECTION\",\"expectedVersion\":" + record.getVersion() + "}"))
                .andExpect(status().isOk());
        var doc = (ObjectNode) mapper.readTree(backup.exportJson());
        assertEquals(1, doc.path("memories").get(0).path("version").asInt());
        var expected = doc.path("memories").get(0).deepCopy();
        memories.deleteAll(); anime.deleteAll();
        assertEquals(1, backup.importJson(doc.toString()).get("newMemories"));
        var actual = mapper.readTree(backup.exportJson()).path("memories").get(0);
        assertNotEquals(expected.path("animeKey").asText(), actual.path("animeKey").asText());
        ((ObjectNode) actual).remove("animeKey"); ((ObjectNode) expected).remove("animeKey");
        assertEquals(expected, actual);
        assertTrue(actual.path("watchedDate").isNull());
        assertEquals(1, backup.importJson(backup.exportJson()).get("memories"));
        assertEquals(0, backup.importJson(doc.toString()).get("newMemories"));
        assertEquals(1, memories.count());
    }

    @Test
    void 当不同记忆写了相同文字时应该分别保留且重复恢复幂等() throws Exception {
        var doc = documentWithMemory();
        var list = (ArrayNode) doc.path("memories");
        var another = ((ObjectNode) list.get(0)).deepCopy();
        another.put("key", UUID.randomUUID().toString()); list.add(another);
        assertEquals(1, backup.importJson(doc.toString()).get("newMemories"));
        assertEquals(0, backup.importJson(doc.toString()).get("newMemories"));
        assertEquals(2, memories.count());
    }

    @Test
    void 当本地记忆已经编辑时应该在预览及恢复拒绝旧备份且不覆盖() throws Exception {
        var doc = documentWithMemory();
        var record = memories.findAll().get(0);
        mvc.perform(put("/api/anime/" + record.getAnimeId() + "/memories/" + record.getId()).with(csrf())
                .contentType("application/json").content("{\"content\":\"现在的回望\",\"context\":\"REFLECTION\",\"expectedVersion\":" + record.getVersion() + "}"))
                .andExpect(status().isOk());
        String current = stableExport();
        assertEquals(false, backup.previewJson(doc.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(doc.toString()));
        assertEquals(current, stableExport());
        assertEquals("现在的回望", memories.findAll().get(0).getContent());
    }

    @Test
    void 当同一记忆编号被改到另一作品时应该拒绝恢复() throws Exception {
        var doc = documentWithMemory();
        anime.saveAndFlush(newAnime("另一部作品"));
        var full = (ObjectNode) mapper.readTree(backup.exportJson());
        ((ObjectNode) full.path("memories").get(0)).put("animeKey", full.path("anime").get(1).path("key").asText());
        assertEquals(false, backup.previewJson(full.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(full.toString()));
        assertEquals(doc.path("memories").get(0).path("animeKey").asText(), "a:" + memories.findAll().get(0).getAnimeId());
    }

    @Test
    void 当只预览记忆备份时应该不写入也不改变审计时间() throws Exception {
        var doc = documentWithMemory();
        String before = stableExport();
        assertEquals(true, backup.previewJson(doc.toString()).get("valid"));
        assertEquals(1, backup.previewJson(doc.toString()).get("memories"));
        assertEquals(0, backup.previewJson(doc.toString()).get("newMemories"));
        assertEquals(before, stableExport());
    }

    @Test
    void 当恢复旧版本或旧数组时应该保留本地额外记忆() throws Exception {
        var doc = documentWithMemory();
        doc.put("version", 1); doc.remove("memories");
        backup.importJson(doc.toString()); backup.importJson("[]");
        assertEquals(1, memories.count());
        assertEquals("  结幕还想再听一次音乐\n<script>原文保留</script>  ", memories.findAll().get(0).getContent());
    }

    @Test
    void 当版本一夹带记忆或版本二缺少集合时应该拒绝而非遗漏数据() throws Exception {
        var doc = documentWithMemory();
        var v1 = doc.deepCopy().put("version", 1);
        var v2 = doc.deepCopy(); v2.remove("memories");
        for (var invalid : new ObjectNode[]{v1, v2}) {
            assertEquals(false, backup.previewJson(invalid.toString()).get("valid"));
            assertThrows(IllegalArgumentException.class, () -> backup.importJson(invalid.toString()));
        }
        assertEquals(1, memories.count());
    }

    @Test
    void 当记忆结构内容或日期无效时应该在任何写入前拒绝() throws Exception {
        var doc = documentWithMemory();
        memories.deleteAll();
        String before = stableExport();
        String[] fields = {"key", "animeKey", "content", "liked", "scope", "context", "numericContext", "watchedDate", "arrayWatchedDate", "arrayCreatedAt", "subMicrosecond", "fractionalVersion", "overflowVersion", "version", "createdAt", "updatedAt"};
        for (String field : fields) {
            var invalid = doc.deepCopy(); var entry = (ObjectNode) invalid.path("memories").get(0);
            switch (field) {
                case "key" -> entry.put(field, "wrong-key");
                case "animeKey" -> entry.put(field, "missing");
                case "content" -> entry.put(field, "  \n ");
                case "liked" -> entry.put(field, "字".repeat(2001));
                case "scope" -> entry.put(field, "字".repeat(201));
                case "context" -> entry.put(field, "UNKNOWN");
                case "numericContext" -> entry.put("context", 1);
                case "arrayWatchedDate" -> entry.putArray("watchedDate").add(2020).add(1).add(1);
                case "arrayCreatedAt" -> entry.putArray("createdAt").add(2020).add(1).add(1).add(1).add(2).add(3);
                case "subMicrosecond" -> entry.put("createdAt", "2025-01-01T12:00:00.123456789");
                case "fractionalVersion" -> entry.put("version", 0.9);
                case "overflowVersion" -> entry.put("version", new java.math.BigInteger("9223372036854775808"));
                case "watchedDate" -> entry.put(field, LocalDate.now().plusDays(1).toString());
                case "version" -> entry.put(field, -1);
                case "createdAt" -> entry.putNull(field);
                case "updatedAt" -> entry.put(field, "1000-01-01T00:00:00");
            }
            assertEquals(false, backup.previewJson(invalid.toString()).get("valid"), field);
            assertThrows(IllegalArgumentException.class, () -> backup.importJson(invalid.toString()), field);
            assertEquals(before, stableExport(), field);
        }
        var duplicate = doc.deepCopy(); ((ArrayNode) duplicate.path("memories")).add(duplicate.path("memories").get(0).deepCopy());
        assertEquals(false, backup.previewJson(duplicate.toString()).get("valid"));
        var nullList = doc.deepCopy(); nullList.putNull("memories");
        assertEquals(false, backup.previewJson(nullList.toString()).get("valid"));
        var oversized = doc.deepCopy(); ((ObjectNode) oversized.path("memories").get(0)).put("content", "字".repeat(16001));
        assertEquals(false, backup.previewJson(oversized.toString()).get("valid"));
    }

    @Test
    void 当记忆保存中途失败时应该回滚全部作品标签逐集与记忆() throws Exception {
        var doc = (ObjectNode) mapper.readTree(BackupServiceTest.BACKUP);
        var memoryDoc = documentWithMemory();
        var entry = ((ObjectNode) memoryDoc.path("memories").get(0)).deepCopy();
        entry.put("animeKey", "a:700");
        doc.put("version", 2); doc.putArray("memories").add(entry);
        memories.deleteAll(); anime.deleteAll();
        doThrow(new IllegalStateException("模拟记忆恢复失败")).when(memories).save(any(AnimeMemory.class));
        assertThrows(RuntimeException.class, () -> backup.importJson(doc.toString()));
        assertEquals(0, anime.count()); assertEquals(0, tags.count()); assertEquals(0, groups.count());
        assertEquals(0, episodes.count()); assertEquals(0, memories.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM anime_group_relation", Integer.class));
    }

    private ObjectNode documentWithMemory() throws Exception {
        Long id = anime.saveAndFlush(newAnime("观影记忆验收")).getId();
        String payload = "{\"key\":\"" + UUID.randomUUID() + "\",\"content\":\"  结幕还想再听一次音乐\\n<script>原文保留</script>  \","
                + "\"liked\":\"演出与音乐\",\"disliked\":\"部分剧情\",\"scope\":\"电影\",\"context\":\"INITIAL\",\"watchedDate\":null}";
        mvc.perform(post("/api/anime/" + id + "/memories").with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isOk());
        return (ObjectNode) mapper.readTree(backup.exportJson());
    }

    private Anime newAnime(String name) {
        Anime a = new Anime(); a.setName(name); a.setStatus(AnimeStatus.WATCHING);
        a.setCurrentEpisode(0); a.setTotalEpisodes(12); return a;
    }

    private String stableExport() throws Exception {
        var doc = (ObjectNode) mapper.readTree(backup.exportJson()); doc.remove("exportedAt"); return doc.toString();
    }
}
