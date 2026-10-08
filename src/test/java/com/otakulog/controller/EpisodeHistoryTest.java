package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.entity.Anime;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.EpisodeRecordSource;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:episode_history;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class EpisodeHistoryTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnimeRepository anime;
    @SpyBean private EpisodeRecordRepository records;
    private Long id;

    @BeforeEach
    void 准备隔离数据() {
        records.deleteAll(); anime.deleteAll();
        Anime a = new Anime(); a.setName("逐集验收"); a.setTotalEpisodes(12); a.setCurrentEpisode(3);
        a.setStatus(AnimeStatus.WATCHING); a.setWatchStartDate(LocalDate.of(2024, 1, 1));
        id = anime.saveAndFlush(a).getId();
        record(1, LocalDate.of(2024, 1, 2), EpisodeRecordSource.LEGACY);
        record(2, null, EpisodeRecordSource.IMPORT);
    }

    @Test
    void 当查询逐集历史时应该区分缺失未知日期和历史来源且不写入() throws Exception {
        JsonNode result = page(0, 20);
        assertEquals(3, result.path("totalRows").asInt());
        assertEquals("LEGACY", result.path("entries").get(0).path("source").asText());
        assertTrue(result.path("entries").get(1).path("watchedDate").isNull());
        assertFalse(result.path("entries").get(2).path("recorded").asBoolean());
        assertEquals(2, records.count());
    }

    @Test
    void 当进度很大时应该分页返回而不创建全部空记录() throws Exception {
        Anime a = anime.findById(id).orElseThrow(); a.setCurrentEpisode(Integer.MAX_VALUE); anime.saveAndFlush(a);
        JsonNode result = page(1073741823, 2);
        assertEquals(1, result.path("entries").size());
        assertEquals(Integer.MAX_VALUE, result.path("entries").get(0).path("episodeNumber").asInt());
        assertEquals(2, records.count());
    }

    @Test
    void 当历史记录在当前进度外时应该仍可查询和校正() throws Exception {
        record(8, null, EpisodeRecordSource.IMPORT);
        JsonNode entry = page(1, 3).path("entries").get(0);
        assertEquals(8, entry.path("episodeNumber").asInt());
        save(8, "2024-02-03", entry, 200);
        assertEquals(LocalDate.of(2024, 2, 3), records.findByAnimeIdAndEpisodeNumber(id, 8).orElseThrow().getWatchedDate());
        assertEquals(3, anime.findById(id).orElseThrow().getCurrentEpisode());
    }

    @Test
    void 当主动核对历史日期时应该转为手动来源且不改作品进度和起止日期() throws Exception {
        save(1, "2024-01-02", entry(1), 200);
        assertEquals(EpisodeRecordSource.MANUAL, records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow().getSource());
        Anime a = anime.findById(id).orElseThrow();
        assertEquals(3, a.getCurrentEpisode()); assertEquals(AnimeStatus.WATCHING, a.getStatus());
        assertEquals(LocalDate.of(2024, 1, 1), a.getWatchStartDate()); assertNull(a.getEndDate());
    }

    @Test
    void 当补录缺失集时应该只新增该集的手动记录() throws Exception {
        save(3, "2024-02-03", entry(3), 200);
        assertEquals(3, records.count());
        assertEquals(EpisodeRecordSource.MANUAL, records.findByAnimeIdAndEpisodeNumber(id, 3).orElseThrow().getSource());
    }

    @Test
    void 当明确日期未变时应该保留来源和更新时间() throws Exception {
        JsonNode before = entry(2);
        save(2, null, before, 200);
        assertEquals(before, entry(2));
    }

    @Test
    void 当清空日期时应该保留未知日期记录而非删除或填今天() throws Exception {
        save(1, null, entry(1), 200);
        EpisodeRecord r = records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow();
        assertNull(r.getWatchedDate()); assertEquals(EpisodeRecordSource.MANUAL, r.getSource());
        assertEquals(2, records.count());
    }

    @Test
    void 当快照已过期时应该返回冲突且保留较新日期() throws Exception {
        JsonNode before = entry(1);
        save(1, "2024-02-03", before, 200);
        save(1, "2024-03-04", before, 409);
        assertEquals("2024-02-03", entry(1).path("watchedDate").asText());
    }

    @Test
    void 当缺失快照对应的集已新增时应该拒绝覆盖() throws Exception {
        JsonNode before = entry(3);
        record(3, LocalDate.of(2024, 1, 3), EpisodeRecordSource.WATCHED);
        save(3, "2024-03-04", before, 409);
    }

    @Test
    void 当记录删除后重建时应该拒绝旧记录快照() throws Exception {
        JsonNode before = entry(1);
        records.deleteById(before.path("recordId").asLong());
        record(1, LocalDate.of(2024, 1, 2), EpisodeRecordSource.LEGACY);
        save(1, "2024-01-02", before, 409);
    }

    @Test
    void 当新增进度之外的未看集时应该拒绝且不推进() throws Exception {
        var expected = mapper.createObjectNode().put("episodeNumber", 4).put("recorded", false);
        save(4, "2024-01-02", expected, 400);
        assertEquals(2, records.count()); assertEquals(3, anime.findById(id).orElseThrow().getCurrentEpisode());
    }

    @Test
    void 当日期无效或未来或超出存储范围时应该拒绝() throws Exception {
        JsonNode expected = entry(1);
        for (String value : new String[]{"2024-02-30", "0999-01-01", "10000-01-01", LocalDate.now().plusDays(1).toString()})
            save(1, value, expected, 400);
        assertEquals(expected, entry(1));
    }

    @Test
    void 当没有完整快照或集号不符时应该拒绝写入() throws Exception {
        save(1, null, null, 400);
        save(1, null, mapper.createObjectNode(), 400);
        save(1, null, entry(2), 400);
        save(3, null, mapper.createObjectNode().put("episodeNumber", 3).put("recorded", false).put("recordId", 1), 400);
        mvc.perform(put("/api/anime/" + id + "/episodes/1").with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().set("expected", entry(1)).toString())).andExpect(status().isBadRequest());
    }

    @Test
    void 当观看时记录的日期未变时应该保留原始来源和审计时间() throws Exception {
        EpisodeRecord r = records.findByAnimeIdAndEpisodeNumber(id, 1).orElseThrow();
        r.setSource(EpisodeRecordSource.WATCHED); records.saveAndFlush(r);
        JsonNode before = entry(1); save(1, "2024-01-02", before, 200);
        assertEquals(before, entry(1));
    }

    @Test
    void 当填写当天时应该保留用户明确填写值() throws Exception {
        save(1, LocalDate.now().toString(), entry(1), 200);
        assertEquals(LocalDate.now().toString(), entry(1).path("watchedDate").asText());
    }

    @Test
    void 当页码或集号非法时应该返回参数错误() throws Exception {
        for (String query : new String[]{"page=-1", "size=0", "size=51"})
            mvc.perform(get("/api/anime/" + id + "/episodes?" + query)).andExpect(status().isBadRequest());
        save(0, null, entry(1), 400);
    }

    @Test
    void 当作品不存在时应该返回未找到() throws Exception {
        mvc.perform(get("/api/anime/" + Long.MAX_VALUE + "/episodes")).andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void 当未登录时应该拒绝读取观看历史() throws Exception {
        mvc.perform(get("/api/anime/" + id + "/episodes")).andExpect(status().isUnauthorized());
    }

    @Test
    void 当缺少CSRF时应该拒绝补录() throws Exception {
        mvc.perform(put("/api/anime/" + id + "/episodes/1").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 当保存失败时应该回滚日期来源并保留作品() throws Exception {
        JsonNode before = entry(1);
        doThrow(new IllegalStateException("模拟逐集写入失败")).when(records).saveAndFlush(any(EpisodeRecord.class));
        save(1, "2024-02-03", before, 500);
        assertEquals(before, entry(1)); assertEquals(3, anime.findById(id).orElseThrow().getCurrentEpisode());
    }

    private void record(int number, LocalDate date, EpisodeRecordSource source) {
        EpisodeRecord r = new EpisodeRecord(); r.setAnimeId(id); r.setEpisodeNumber(number);
        r.setWatchedDate(date); r.setSource(source); records.saveAndFlush(r);
    }

    private JsonNode page(int page, int size) throws Exception {
        String body = mvc.perform(get("/api/anime/" + id + "/episodes?page=" + page + "&size=" + size))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data");
    }

    private JsonNode entry(int number) throws Exception { return page(0, 20).path("entries").get(number - 1); }

    private void save(int number, String date, JsonNode expected, int status) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("watchedDate", date); body.put("expected", expected);
        mvc.perform(put("/api/anime/" + id + "/episodes/" + number).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().is(status));
    }
}
