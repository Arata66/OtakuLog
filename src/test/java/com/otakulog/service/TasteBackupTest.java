package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.*;
import com.otakulog.enums.*;
import com.otakulog.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:taste_backup;DB_CLOSE_DELAY=-1")
class TasteBackupTest {
    @Autowired BackupService backup;
    @Autowired AnimeRepository anime;
    @Autowired AnimeMemoryRepository memories;
    @Autowired MemoryInsightRepository insights;
    @Autowired AiQuotaRepository quota;
    @Autowired TasteAiService ai;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @SpyBean AiCallRepository calls;

    @BeforeEach void 准备隔离数据() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS anime_group_relation (group_id BIGINT NOT NULL, anime_id BIGINT NOT NULL, PRIMARY KEY(group_id,anime_id))");
        insights.deleteAll(); calls.deleteAll(); memories.deleteAll(); anime.deleteAll(); quota.deleteAll();
        var q = new AiQuota(); q.setId(1L); q.setQuotaDate(LocalDate.now()); q.setReservedCalls(4); quota.saveAndFlush(q);
    }

    @Test void 当恢复版本三时应该往返保留模型建议用户修订拒绝失效与未知用量() throws Exception {
        var doc = fixture();
        assertEquals(true, backup.previewJson(doc.toString()).get("valid"));
        assertEquals(1, backup.importJson(doc.toString()).get("newInsights"));
        assertEquals(2, backup.importJson(doc.toString()).get("aiCalls"));
        var actual = mapper.readTree(backup.exportJson());
        assertEquals(5, actual.path("version").asInt());
        assertEquals(doc.path("insights"), actual.path("insights"));
        assertEquals(doc.path("aiCalls"), actual.path("aiCalls"));
        assertFalse(actual.toString().contains("historyOnly"));
        assertEquals(4, quota.findById(1L).orElseThrow().getReservedCalls());
    }

    @Test void 当恢复旧格式时应该保留观点调用与本地额度() throws Exception {
        var doc = fixture(); backup.importJson(doc.toString());
        for (int version : new int[]{1, 2}) {
            var old = doc.deepCopy(); old.put("version", version); old.remove(List.of("insights", "aiCalls"));
            if (version == 1) old.remove("memories");
            backup.importJson(old.toString());
        }
        backup.importJson("[]");
        assertEquals(1, insights.count()); assertEquals(2, calls.count());
        assertEquals(4, quota.findById(1L).orElseThrow().getReservedCalls());
    }

    @Test void 当同UUID任意字段不同或来源非法时应该整批拒绝() throws Exception {
        var doc = fixture(); backup.importJson(doc.toString());
        var conflict = doc.deepCopy(); ((ObjectNode) conflict.path("insights").get(0)).put("suggestedFactor", "不同");
        assertEquals(false, backup.previewJson(conflict.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(conflict.toString()));
        String[] invalidFields = {"missing", "future", "quote", "whitespace", "micro", "tokens", "type", "manual", "uuid", "hash", "link", "audit"};
        for (var field : invalidFields) {
            var invalid = doc.deepCopy(); var i = (ObjectNode) invalid.path("insights").get(0); var c = (ObjectNode) invalid.path("aiCalls").get(0);
            switch (field) {
                case "missing" -> i.remove("sourceVersion");
                case "future" -> i.put("sourceVersion", 3);
                case "quote" -> { i.put("sourceVersion", 2); i.put("quote", "不存在"); }
                case "whitespace" -> i.put("factor", "\u00a0\u2003");
                case "micro" -> c.put("createdAt", "2026-01-01T00:00:00.123456789");
                case "tokens" -> c.put("promptTokens", -1);
                case "type" -> i.put("sourceVersion", "0");
                case "manual" -> i.put("origin", "MANUAL");
                case "uuid" -> c.put("requestKey", "INVALID");
                case "hash" -> c.put("inputHash", "A".repeat(64));
                case "link" -> i.put("aiCallKey", UUID.randomUUID().toString());
                case "audit" -> c.put("updatedAt", "1000-01-01T00:00:00");
            }
            assertEquals(false, backup.previewJson(invalid.toString()).get("valid"), field);
            assertThrows(IllegalArgumentException.class, () -> backup.importJson(invalid.toString()), field);
        }
        assertEquals(1, insights.count()); assertEquals(2, calls.count());
    }

    @Test void 当恢复中途保存调用失败时应该连同额度与观点全部回滚() throws Exception {
        var doc = fixture();
        doThrow(new IllegalStateException("模拟保存失败")).when(calls).save(any(AiCall.class));
        assertThrows(RuntimeException.class, () -> backup.importJson(doc.toString()));
        assertEquals(0, insights.count()); assertEquals(0, calls.count());
        assertEquals(1, anime.count()); assertEquals(1, memories.count());
        assertEquals(4, quota.findById(1L).orElseThrow().getReservedCalls());
    }

    @Test void 当恢复运行中历史调用时应该显示未知且不会改动已有本地调用标记() throws Exception {
        var doc = fixture(); var c = (ObjectNode) doc.path("aiCalls").get(1);
        c.put("status", "RUNNING"); c.putNull("completedAt");
        String key = c.path("requestKey").asText();
        backup.importJson(doc.toString());
        assertTrue(calls.findByRequestKey(key).orElseThrow().isHistoryOnly());
        assertTrue(ai.get(key).message().contains("未知"));
        backup.importJson(doc.toString());
        assertTrue(calls.findByRequestKey(key).orElseThrow().isHistoryOnly());
    }

    @Test void 当版本一二夹带新集合或版本三缺失集合时应该拒绝() throws Exception {
        var doc = fixture();
        for (int version : new int[]{1, 2}) {
            var invalid = doc.deepCopy().put("version", version);
            assertEquals(false, backup.previewJson(invalid.toString()).get("valid"));
        }
        doc.remove("aiCalls"); assertEquals(false, backup.previewJson(doc.toString()).get("valid"));
    }

    @Test void 当合并当天调用账本更多时应该提升额度且孤立历史版本不受当前来源限制() throws Exception {
        var doc = fixture();
        var list = (com.fasterxml.jackson.databind.node.ArrayNode) doc.path("aiCalls");
        for (int n = 0; n < 4; n++) {
            var extra = ((ObjectNode) list.get(1)).deepCopy(); extra.put("requestKey", UUID.randomUUID().toString());
            extra.put("memoryKey", doc.path("memories").get(0).path("key").asText()); list.add(extra);
        }
        backup.importJson(doc.toString()); assertEquals(6, quota.findById(1L).orElseThrow().getReservedCalls());
        backup.importJson("[]"); assertEquals(6, quota.findById(1L).orElseThrow().getReservedCalls());
        var different = doc.deepCopy(); ((ObjectNode) different.path("aiCalls").get(1)).put("reasoningTokens", 1);
        assertEquals(false, backup.previewJson(different.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(different.toString()));
    }

    @Test void 当手工当前观点恢复时应该重映射来源并保留完全一致的审计() throws Exception {
        var doc = fixture(); var i = (ObjectNode) doc.path("insights").get(0);
        i.put("origin", "MANUAL"); i.put("sourceVersion", 2); i.put("quote", "人物关系"); i.put("status", "CONFIRMED");
        for (String field : new String[]{"aiCallKey", "suggestedQuoteField", "suggestedQuote", "suggestedStatement", "suggestedDirection", "suggestedAspect", "suggestedFactor"}) i.putNull(field);
        memories.deleteAll(); anime.deleteAll();
        backup.importJson(doc.toString());
        assertEquals(doc.path("insights"), mapper.readTree(backup.exportJson()).path("insights"));
        assertEquals(memories.findAll().get(0).getId(), insights.findAll().get(0).getMemoryId());
        assertEquals(0, backup.importJson(doc.toString()).get("newInsights"));
    }

    @Test void 当合并后观点超过记忆容量时应该在预览和写入前拒绝() throws Exception {
        var doc = fixture(); var memory = memories.findAll().get(0);
        for (int n = 0; n < 200; n++) {
            var i = new MemoryInsight(); i.setInsightKey(UUID.randomUUID().toString()); i.setMemoryId(memory.getId());
            i.setSourceVersion(2L); i.setQuoteField(InsightQuoteField.CONTENT); i.setQuote("人物"); i.setStatement("喜欢人物");
            i.setDirection(InsightDirection.LIKE); i.setAspect(InsightAspect.CHARACTER); i.setFactor("人物");
            i.setStatus(InsightStatus.CONFIRMED); i.setOrigin(InsightOrigin.MANUAL); insights.save(i);
        }
        assertEquals(false, backup.previewJson(doc.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(doc.toString()));
        assertEquals(200, insights.count()); assertEquals(0, calls.count());
    }

    @Test void 当选文字数越界或观点是Unicode空白时应该在预览拒绝() throws Exception {
        var doc = fixture();
        for (int characters : new int[]{0, 6001}) {
            var invalid = doc.deepCopy(); ((ObjectNode) invalid.path("aiCalls").get(0)).put("selectedCharacters", characters);
            assertEquals(false, backup.previewJson(invalid.toString()).get("valid"), "字数" + characters);
            assertThrows(IllegalArgumentException.class, () -> backup.importJson(invalid.toString()));
        }
        for (String field : new String[]{"quote", "statement", "factor", "suggestedQuote", "suggestedStatement", "suggestedFactor"}) {
            for (String whitespace : new String[]{"\u0085", "\u0085\u00a0\u2003\u001c"}) {
                var invalid = doc.deepCopy(); ((ObjectNode) invalid.path("insights").get(0)).put(field, whitespace);
                assertEquals(false, backup.previewJson(invalid.toString()).get("valid"), field);
                assertThrows(IllegalArgumentException.class, () -> backup.importJson(invalid.toString()));
            }
        }
    }

    ObjectNode fixture() throws Exception {
        Anime a = new Anime(); a.setName("虚构口味备份"); a.setCurrentEpisode(0); a.setTotalEpisodes(12); a.setStatus(AnimeStatus.WATCHING); anime.saveAndFlush(a);
        AnimeMemory m = new AnimeMemory(); m.setMemoryKey(UUID.randomUUID().toString()); m.setAnimeId(a.getId()); m.setContent("现在喜欢人物关系"); m.setContext(MemoryContext.NOTE); m.setVersion(2L); memories.saveAndFlush(m);
        ObjectNode doc = (ObjectNode) mapper.readTree(backup.exportJson()); doc.put("version", 3);
        doc.remove(List.of("externalCollections", "importBatches", "recommendationPreferences", "recommendationFeedback"));
        String callKey = UUID.randomUUID().toString();
        var c = doc.putArray("aiCalls").addObject();
        c.put("requestKey", callKey); c.put("memoryKey", m.getMemoryKey()); c.put("sourceVersion", 0); c.put("inputHash", "a".repeat(64)); c.put("model", "deepseek-flash"); c.put("promptVersion", "v1"); c.put("selectedCharacters", 8); c.put("status", "SUCCEEDED"); c.putNull("errorCategory"); c.put("promptTokens", 10); c.put("completionTokens", 5); c.putNull("cachedTokens"); c.putNull("reasoningTokens");
        c.put("createdAt", LocalDate.now().atStartOfDay().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)); c.put("updatedAt", LocalDate.now().atStartOfDay().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)); c.put("completedAt", LocalDate.now().atStartOfDay().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        var orphan = c.deepCopy(); orphan.put("requestKey", UUID.randomUUID().toString()); orphan.put("memoryKey", UUID.randomUUID().toString()); orphan.put("sourceVersion", 99); orphan.put("status", "STALE"); orphan.putNull("promptTokens"); orphan.putNull("completionTokens"); ((com.fasterxml.jackson.databind.node.ArrayNode) doc.path("aiCalls")).add(orphan);
        var i = doc.putArray("insights").addObject();
        i.put("key", UUID.randomUUID().toString()); i.put("memoryKey", m.getMemoryKey()); i.put("sourceVersion", 1); i.put("quoteField", "CONTENT"); i.put("quote", "旧版人物"); i.put("statement", "用户修订"); i.put("direction", "DISLIKE"); i.put("aspect", "CHARACTER"); i.put("factor", "人物关系"); i.put("status", "REJECTED"); i.put("origin", "AI"); i.put("aiCallKey", callKey);
        i.put("suggestedQuoteField", "CONTENT"); i.put("suggestedQuote", "更旧版人物"); i.put("suggestedStatement", "模型原建议"); i.put("suggestedDirection", "LIKE"); i.put("suggestedAspect", "CHARACTER"); i.put("suggestedFactor", "人物"); i.put("version", 3);
        i.put("createdAt", "2026-01-01T00:00:00.123456"); i.put("updatedAt", "2026-01-02T00:00:00.123456");
        return doc;
    }
}
