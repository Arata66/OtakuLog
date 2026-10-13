package com.otakulog.service;

import com.otakulog.dto.BangumiCollectionSnapshot;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bangumi_preview;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class BangumiPreviewTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired AnimeRepository anime;
    @Autowired EpisodeRecordRepository episodes;
    @Autowired com.otakulog.repository.ImportLockRepository locks;
    @org.springframework.boot.test.mock.mockito.SpyBean com.otakulog.repository.ImportBatchRepository batches;
    @org.springframework.boot.test.mock.mockito.SpyBean com.otakulog.repository.ExternalCollectionRepository collections;
    @Autowired BangumiImportPreviewService previewService;
    @Autowired BackupService backup;
    @Autowired AnimeService animeService;
    @Autowired com.otakulog.repository.AiQuotaRepository quota;
    @MockBean BangumiService bangumi;
    @BeforeEach void 准备虚构收藏() {
        episodes.deleteAll(); anime.deleteAll();
        batches.deleteAll(); collections.deleteAll();
        BackupTestQuota.seed(quota);
        org.springframework.test.util.ReflectionTestUtils.setField(previewService, "clock", java.time.Clock.systemDefaultZone());
        if (!locks.existsById(1L)) { var lock = new com.otakulog.entity.ImportLock(); lock.setId(1L); locks.saveAndFlush(lock); }
        when(bangumi.getCompleteUserCollections("test")).thenReturn(new BangumiCollectionSnapshot(2,
                List.of(row(91001, 2, 3), row(91002, 4, 0)), false));
    }
    Map<String, Object> row(int id, int type, int eps) {
        return Map.of("subjectId", id, "nameCn", "虚构收藏" + id, "type", type, "eps", eps,
                "epStatus", 0, "rate", 8, "tags", List.of("自己的标签"), "comment", "仅为外部收藏评论");
    }
    String preview() throws Exception {
        var response = mvc.perform(post("/api/bangumi/import/preview").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readCount").value(2))
                .andExpect(jsonPath("$.data.newCount").value(1)).andExpect(jsonPath("$.data.reviewCount").value(1))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("previewKey").asText();
    }
    String request(String key, String requestKey) throws Exception {
        return mapper.writeValueAsString(Map.of("previewKey", key, "requestKey", requestKey, "importScores", true, "importTags", true));
    }
    @Test void 当读取预览时应该零写入并逐条显示需要核对的原因() throws Exception {
        preview(); assertEquals(0, anime.count()); assertEquals(0, episodes.count());
    }
    @Test void 当确认预览并重试同一请求时应该只导入一次且保留未知观看日期() throws Exception {
        String key = preview(), body = request(key, UUID.randomUUID().toString());
        for (int n = 0; n < 2; n++) mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1)).andExpect(jsonPath("$.data.externalSaved").value(2));
        assertEquals(1, anime.count()); assertEquals(3, episodes.count());
        assertEquals(8.0, anime.findAll().get(0).getScore());
        assertTrue(episodes.findAll().stream().allMatch(e -> e.getWatchedDate() == null));
    }
    @Test void 当其他登录身份确认预览时应该拒绝且不写入() throws Exception {
        String key = preview();
        mvc.perform(post("/api/bangumi/import/confirm").with(user("other")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(key, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()); assertEquals(0, anime.count());
    }
    @Test void 当确认缺少令牌或客户端伪造预览编号时应该拒绝() throws Exception {
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester"))
                .contentType(MediaType.APPLICATION_JSON).content(request(UUID.randomUUID().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(UUID.randomUUID().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
    }
    @Test void 当完整备份往返时应该保留外部原文和导入防重结果且不外发请求() throws Exception {
        String key = preview(), body = request(key, UUID.randomUUID().toString());
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        var document = mapper.readTree(backup.exportJson());
        assertEquals(6, document.path("version").asInt()); assertEquals(2, document.path("externalCollections").size());
        assertEquals(1, document.path("importBatches").size());
        collections.deleteAll(); batches.deleteAll(); episodes.deleteAll(); anime.deleteAll();
        clearInvocations(bangumi);
        backup.importJson(mapper.writeValueAsString(document));
        assertEquals(2, collections.count()); assertEquals(1, batches.count()); verifyNoInteractions(bangumi);
        var restored = mapper.readTree(backup.exportJson());
        assertEquals(document.path("externalCollections"), restored.path("externalCollections"));
        assertEquals(document.path("importBatches"), restored.path("importBatches"));
    }
    @Test void 当确认前本地作品发生变化时应该整批拒绝并要求重新预览() throws Exception {
        String key = preview(); var value = new com.otakulog.entity.Anime(); value.setName("后来新增的虚构作品"); anime.saveAndFlush(value);
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(key, UUID.randomUUID().toString())))
                .andExpect(status().isConflict()); assertEquals(0, collections.count()); assertEquals(1, anime.count());
    }
    @Test void 当预览过期时应该拒绝确认且不写入() throws Exception {
        String key = preview(); org.springframework.test.util.ReflectionTestUtils.setField(previewService, "clock",
                java.time.Clock.fixed(java.time.Instant.now().plusSeconds(901), java.time.ZoneId.systemDefault()));
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(key, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()); assertEquals(0, anime.count());
    }
    @Test void 当导入中途外部记录保存失败时应该回滚作品逐集与整批镜像() throws Exception {
        String key = preview();
        doThrow(new IllegalStateException("虚构中途失败")).when(collections).save(
                org.mockito.ArgumentMatchers.argThat(e -> e.getSubjectId() == 91002));
        mvc.perform(post("/api/bangumi/import/confirm").with(user("tester")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(key, UUID.randomUUID().toString())))
                .andExpect(status().is5xxServerError());
        assertEquals(0, anime.count()); assertEquals(0, episodes.count()); assertEquals(0, collections.count()); assertEquals(0, batches.count());
    }
    @Test void 当同一请求键换成不同选项时应该保留原结果并拒绝重发() throws Exception {
        String key = preview(), requestKey = UUID.randomUUID().toString();
        var result = previewService.confirm("tester", new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, requestKey, true, true));
        assertEquals(1, result.created());
        assertThrows(com.otakulog.common.ConflictException.class, () -> previewService.confirm("tester",
                new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, requestKey, false, true)));
        assertEquals(1, anime.count()); assertEquals(1, batches.count());
    }
    @Test void 当备份同一外部编号内容不同或内层字段缺失时应该整批冲突且不覆盖原文() throws Exception {
        String key = preview(); previewService.confirm("tester", new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, UUID.randomUUID().toString(), false, false));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.exportJson());
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("externalCollections").get(0);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(entry.path("payload").asText());
        payload.put("comment", "另一份虚构评论"); entry.put("payload", mapper.writeValueAsString(payload));
        assertEquals(false, backup.previewJson(document.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(document.toString()));
        payload.remove("rate"); entry.put("payload", mapper.writeValueAsString(payload));
        assertEquals(false, backup.previewJson(document.toString()).get("valid"));
        assertEquals(2, collections.count());
    }
    @Test void 当调用旧直接导入接口时应该要求预览确认且不绕过新流程() throws Exception {
        mvc.perform(post("/api/bangumi/import/test").with(user("tester")).with(csrf()))
                .andExpect(status().isBadRequest()); verifyNoInteractions(bangumi); assertEquals(0, anime.count());
    }
    @Test void 当外部搁置作品无法导入进度时应该仍从推荐候选中排除() throws Exception {
        String key = preview(); previewService.confirm("tester", new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, UUID.randomUUID().toString(), true, true));
        var held = new com.otakulog.dto.BangumiResult(); held.setId(91002); held.setName("未入库的搁置作品"); held.setNameCn(held.getName());
        var fresh = new com.otakulog.dto.BangumiResult(); fresh.setId(91003); fresh.setName("新的虚构候选"); fresh.setNameCn(fresh.getName());
        when(bangumi.searchTaggedSubjects(anyString(), anyInt(), any(), any())).thenReturn(List.of(held, fresh));
        when(bangumi.getSubject(anyInt())).thenAnswer(call -> {
            int id=call.getArgument(0);var d=new com.otakulog.dto.BangumiSubjectDetail();d.setId(id);d.setType(2);d.setName("虚构候选"+id);d.setNameCn(d.getName());d.setAllTags(List.of("自己的标签"));d.setAirDate("2024-01-01");return d;
        });
        var result = animeService.getRecommendations();
        assertTrue(result.stream().anyMatch(r -> r.get("id").equals(91003)));
        assertFalse(result.stream().anyMatch(r -> r.get("id").equals(91002)));
    }
    @Test void 当读取外部收藏时应该始终在数据库事务外且不写入() {
        when(bangumi.getCompleteUserCollections("test")).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new BangumiCollectionSnapshot(1, List.of(row(91001, 2, 3)), false);
        });
        assertEquals(1, previewService.preview("tester", "test").readCount()); assertEquals(0, anime.count());
    }
    @Test void 当外部集合或历史超出备份保护上限时应该在写入前拒绝() throws Exception {
        String key = preview();
        doReturn(100000L).when(collections).count();
        assertThrows(IllegalArgumentException.class, () -> previewService.confirm("tester",
                new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, UUID.randomUUID().toString(), false, false)));
        doReturn(0L).when(collections).count(); doReturn(100000L).when(batches).count();
        assertThrows(IllegalArgumentException.class, () -> previewService.confirm("tester",
                new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, UUID.randomUUID().toString(), false, false)));
        assertEquals(0, anime.count()); assertEquals(0, episodes.count());
        verify(collections, never()).save(org.mockito.ArgumentMatchers.any());
    }
    @Test void 当本地登录名含空格时应该正确绑定身份且仍拒绝含换行的来源账号() {
        assertEquals(2, assertDoesNotThrow(() -> previewService.preview("本地 管理员", "test")).readCount());
        assertThrows(IllegalArgumentException.class, () -> BangumiImportPreviewService.account("te\nst\nuser"));
    }
    @Test void 当空目标库恢复错误原文类型时应该拒绝且不产生任何记录() throws Exception {
        String key = preview(); previewService.confirm("tester", new com.otakulog.dto.BangumiImportDTO.ConfirmRequest(key, UUID.randomUUID().toString(), false, false));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.exportJson());
        collections.deleteAll(); batches.deleteAll(); episodes.deleteAll(); anime.deleteAll();
        assertEquals(true, backup.previewJson(document.toString()).get("valid"));
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("externalCollections").get(0);
        String original = entry.path("payload").asText();
        for (String field : List.of("comment", "name", "nameCn", "date", "image", "sourceUpdatedAt", "tags", "subjectId", "type", "eps", "epStatus", "rate")) {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
            if (field.equals("tags")) payload.putArray(field).add(456).add(true);
            else if (List.of("subjectId", "type", "eps", "epStatus", "rate").contains(field)) payload.put(field, payload.path(field).asInt() + 0.0);
            else payload.put(field, 123);
            entry.put("payload", payload.toString());
            assertEquals(false, backup.previewJson(document.toString()).get("valid"), field);
            assertThrows(IllegalArgumentException.class, () -> backup.importJson(document.toString()), field);
        }
        entry.put("payload", original);
        var batch = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("importBatches").get(0);
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(batch.path("resultJson").asText());
        result.put("created", 1.0); batch.put("resultJson", result.toString());
        assertEquals(false, backup.previewJson(document.toString()).get("valid"));
        assertThrows(IllegalArgumentException.class, () -> backup.importJson(document.toString()));
        assertEquals(0, anime.count()); assertEquals(0, episodes.count()); assertEquals(0, collections.count()); assertEquals(0, batches.count());
    }
}
