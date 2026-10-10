package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.Anime;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:anime_memory;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class AnimeMemoryTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnimeRepository anime;
    @Autowired private JdbcTemplate jdbc;
    private Long id;

    @BeforeEach
    void 准备隔离作品() {
        id = newAnime();
    }

    @Test
    void 当保存自由感想时应该保留原文换行和未知观看日期() throws Exception {
        ObjectNode body = request("  此刻的感受\n留给未来  ");
        body.put("liked", "  音乐\n演出  ").put("disliked", "  收束略急  ").put("scope", "  第一季  ");
        JsonNode entry = create(id, body, 200);
        assertEquals(body.path("content"), entry.path("content"));
        assertEquals(body.path("liked"), entry.path("liked"));
        assertEquals(body.path("disliked"), entry.path("disliked"));
        assertEquals(body.path("scope"), entry.path("scope"));
        assertEquals("NOTE", entry.path("context").asText());
        assertTrue(entry.path("watchedDate").isNull());
        assertEquals(0, entry.path("version").asLong());
        assertFalse(entry.path("createdAt").isNull());
        assertEquals(entry, page(id, 0, 20).path("entries").get(0));
        assertNull(anime.findById(id).orElseThrow().getEndDate());
    }

    @Test
    void 当可选文字留空时应该存空值且不裁剪非空原文() throws Exception {
        JsonNode entry = create(id, request("感想").put("liked", " \n ").put("disliked", "").put("scope", "\t"), 200);
        assertTrue(entry.path("liked").isNull());
        assertTrue(entry.path("disliked").isNull());
        assertTrue(entry.path("scope").isNull());
    }

    @Test
    void 当追加回望与重看时应该保留初看感想并按新旧分页() throws Exception {
        JsonNode initial = create(id, request("初看震撼").put("context", "INITIAL").put("watchedDate", "2024-01-01"), 200);
        JsonNode reflection = create(id, request("回望仍喜欢音乐").put("context", "REFLECTION"), 200);
        JsonNode rewatch = create(id, request("重看留意到细节").put("context", "REWATCH"), 200);
        assertEquals(rewatch, page(id, 0, 1).path("entries").get(0));
        assertEquals(reflection, page(id, 1, 1).path("entries").get(0));
        assertEquals(initial, page(id, 2, 1).path("entries").get(0));
        assertEquals(3, page(id, 0, 1).path("totalEntries").asInt());
    }

    @Test
    void 当同一时间存在多条记忆时应该使用记录编号稳定倒序() throws Exception {
        JsonNode first = create(id, request("第一条"), 200);
        JsonNode second = create(id, request("第二条"), 200);
        jdbc.update("UPDATE anime_memory SET created_at = ? WHERE anime_id = ?", "2024-01-01 00:00:00", id);
        assertEquals(second.path("id"), page(id, 0, 1).path("entries").get(0).path("id"));
        assertEquals(first.path("id"), page(id, 1, 1).path("entries").get(0).path("id"));
    }

    @Test
    void 当编辑单条记忆时应该增加版本并保留创建时间和其他记忆() throws Exception {
        JsonNode first = create(id, request("错字"), 200);
        JsonNode other = create(id, request("独立记忆"), 200);
        ObjectNode body = request("改正文字").put("expectedVersion", 0).put("key", first.path("key").asText());
        JsonNode edited = update(id, first.path("id").asLong(), body, 200);
        assertEquals(1, edited.path("version").asLong());
        assertEquals(first.path("createdAt"), edited.path("createdAt"));
        assertNotEquals(first.path("updatedAt"), edited.path("updatedAt"));
        assertEquals(other, page(id, 0, 20).path("entries").get(0));
        assertEquals("改正文字", edited.path("content").asText());
    }

    @Test
    void 当保存相同内容时应该保留版本和更新时间() throws Exception {
        ObjectNode body = request("相同正文");
        JsonNode first = create(id, body, 200);
        body.put("expectedVersion", 0).remove("key");
        assertEquals(first, update(id, first.path("id").asLong(), body, 200));
    }

    @Test
    void 当删除单条记忆时应该保留作品和其他记忆() throws Exception {
        JsonNode first = create(id, request("删除这一条"), 200);
        JsonNode other = create(id, request("保留这一条"), 200);
        remove(id, first.path("id").asLong(), "0", 200);
        assertEquals(1, page(id, 0, 20).path("totalEntries").asInt());
        assertEquals(other, page(id, 0, 20).path("entries").get(0));
        assertTrue(anime.existsById(id));
    }

    @Test
    void 当相同键重复提交同一内容时应该返回原记录防止重复追加() throws Exception {
        ObjectNode body = request("重试正文");
        JsonNode first = create(id, body, 200);
        assertEquals(first, create(id, body, 200));
        assertEquals(1, page(id, 0, 20).path("totalEntries").asInt());
    }

    @Test
    void 当复用键提交其他正文或作品时应该拒绝且保留原记录() throws Exception {
        ObjectNode body = request("原正文");
        JsonNode first = create(id, body, 200);
        create(id, body.deepCopy().put("content", "其他正文"), 409);
        create(newAnime(), body, 409);
        assertEquals(first, page(id, 0, 20).path("entries").get(0));
    }

    @Test
    void 当使用旧版本编辑或删除时应该冲突且保留较新感想() throws Exception {
        JsonNode first = create(id, request("原文"), 200);
        long memoryId = first.path("id").asLong();
        ObjectNode body = request("新正文").put("expectedVersion", 0); body.remove("key");
        JsonNode edited = update(id, memoryId, body, 200);
        update(id, memoryId, body.deepCopy().put("content", "旧窗口正文"), 409);
        update(id, memoryId, body, 409);
        remove(id, memoryId, "0", 409);
        assertEquals(edited, page(id, 0, 20).path("entries").get(0));
    }

    @Test
    void 当操作其他作品记忆或不存在作品时应该返回未找到() throws Exception {
        JsonNode first = create(id, request("原文"), 200);
        long otherId = newAnime();
        ObjectNode edit = request("编辑").put("expectedVersion", 0); edit.remove("key");
        update(otherId, first.path("id").asLong(), edit, 404);
        remove(otherId, first.path("id").asLong(), "0", 404);
        create(Long.MAX_VALUE, request("正文"), 404);
        mvc.perform(get(url(Long.MAX_VALUE))).andExpect(status().isNotFound());
        update(id, Long.MAX_VALUE, edit, 404);
    }

    @Test
    void 当键缺失或不是规范小写UUID时应该拒绝写入() throws Exception {
        ObjectNode body = request("正文"); body.remove("key"); create(id, body, 400);
        for (String key : new String[]{"", "bad", "1-1-1-1-1", "12345678-ABCD-1234-1234-123456789abc", " " + UUID.randomUUID()})
            create(id, request("正文").put("key", key), 400);
        assertEquals(0, page(id, 0, 20).path("totalEntries").asInt());
    }

    @Test
    void 当正文为空或字段超长时应该拒绝且按Java字符长度接受上限() throws Exception {
        create(id, request(" \n\t "), 400);
        ObjectNode missing = request("正文"); missing.remove("content"); create(id, missing, 400);
        for (String field : new String[]{"content", "liked", "disliked", "scope"}) {
            int limit = "content".equals(field) ? 16000 : "scope".equals(field) ? 200 : 2000;
            create(id, request("正文").put(field, "字".repeat(limit + 1)), 400);
        }
        create(id, request("字".repeat(16000)).put("liked", "好".repeat(2000)).put("disliked", "差".repeat(2000)).put("scope", "季".repeat(200)), 200);
    }

    @Test
    void 当观看日期或语境非法时应该返回参数错误() throws Exception {
        for (String date : new String[]{"2024-02-30", "0999-12-31", "10000-01-01", LocalDate.now().plusDays(1).toString()})
            create(id, request("正文").put("watchedDate", date), 400);
        create(id, request("正文").put("context", "UNKNOWN"), 400);
        ObjectNode arrayDate = request("正文");
        arrayDate.putArray("watchedDate").add(2024).add(1).add(1);
        assertAll(() -> create(id, request("正文").put("context", 0), 400), () -> create(id, arrayDate, 400));
        create(id, request("正文").put("watchedDate", "1000-01-01"), 200);
        create(id, request("正文").put("watchedDate", LocalDate.now().toString()), 200);
    }

    @Test
    void 当版本缺失负数或试图换键时应该拒绝修改() throws Exception {
        JsonNode first = create(id, request("正文"), 200);
        long memoryId = first.path("id").asLong();
        ObjectNode body = request("更改"); body.remove("key");
        update(id, memoryId, body, 400);
        update(id, memoryId, body.deepCopy().put("expectedVersion", -1), 400);
        assertAll(() -> update(id, memoryId, body.deepCopy().put("expectedVersion", 0.9), 400),
                () -> update(id, memoryId, body.deepCopy().put("expectedVersion", new BigInteger("9223372036854775808")), 400),
                () -> update(id, memoryId, body.deepCopy().putNull("expectedVersion"), 400));
        update(id, memoryId, body.deepCopy().put("expectedVersion", 0).put("key", UUID.randomUUID().toString()), 400);
        remove(id, memoryId, "-1", 400);
        mvc.perform(delete(url(id) + "/" + memoryId).with(csrf())).andExpect(status().isBadRequest());
        assertEquals(first, page(id, 0, 20).path("entries").get(0));
    }

    @Test
    void 当页码或分页大小非法时应该返回参数错误() throws Exception {
        for (String query : new String[]{"page=-1", "size=0", "size=51", "page=abc"})
            mvc.perform(get(url(id) + "?" + query)).andExpect(status().isBadRequest());
        JsonNode page = page(id, Integer.MAX_VALUE, 50);
        assertEquals(id.longValue(), page.path("animeId").asLong());
        assertEquals(0, page.path("entries").size());
    }

    @Test
    @WithAnonymousUser
    void 当未登录时应该拒绝读取和写入观影记忆() throws Exception {
        mvc.perform(get(url(id))).andExpect(status().isUnauthorized());
        mvc.perform(post(url(id)).with(csrf()).contentType("application/json").content(request("正文").toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 当缺少CSRF时应该拒绝新增编辑和删除() throws Exception {
        mvc.perform(post(url(id)).contentType("application/json").content(request("正文").toString())).andExpect(status().isForbidden());
        mvc.perform(put(url(id) + "/1").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(url(id) + "/1?expectedVersion=0")).andExpect(status().isForbidden());
    }

    @Test
    void 当删除作品时应该同步删除记忆避免孤儿() throws Exception {
        create(id, request("删除的作品记忆"), 200);
        mvc.perform(delete("/api/anime/" + id).with(csrf())).andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM anime_memory WHERE anime_id = ?", Long.class, id));
    }

    @Test
    void 当批量删除作品时应该同步删除全部记忆且保留其他作品() throws Exception {
        Long second = newAnime(), remaining = newAnime();
        create(id, request("第一部"), 200); create(second, request("第二部"), 200);
        JsonNode untouched = create(remaining, request("保留"), 200);
        mvc.perform(post("/api/anime/batch-delete").with(csrf()).contentType("application/json")
                .content("{\"ids\":[" + id + "," + second + "]}")).andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM anime_memory WHERE anime_id IN (?, ?)", Long.class, id, second));
        assertEquals(untouched, page(remaining, 0, 20).path("entries").get(0));
    }

    private Long newAnime() {
        Anime value = new Anime(); value.setName("虚构记忆验收" + UUID.randomUUID());
        value.setTotalEpisodes(12); value.setCurrentEpisode(3); value.setStatus(AnimeStatus.WATCHING);
        return anime.saveAndFlush(value).getId();
    }
    private String url(Long animeId) { return "/api/anime/" + animeId + "/memories"; }
    private ObjectNode request(String content) { return mapper.createObjectNode().put("key", UUID.randomUUID().toString()).put("content", content); }
    private JsonNode create(Long animeId, ObjectNode body, int code) throws Exception {
        String response = mvc.perform(post(url(animeId)).with(csrf()).contentType("application/json").content(body.toString()))
                .andExpect(status().is(code)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response).path("data");
    }
    private JsonNode update(Long animeId, long memoryId, ObjectNode body, int code) throws Exception {
        String response = mvc.perform(put(url(animeId) + "/" + memoryId).with(csrf()).contentType("application/json").content(body.toString()))
                .andExpect(status().is(code)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response).path("data");
    }
    private void remove(Long animeId, long memoryId, String version, int code) throws Exception {
        mvc.perform(delete(url(animeId) + "/" + memoryId + "?expectedVersion=" + version).with(csrf())).andExpect(status().is(code));
    }
    private JsonNode page(Long animeId, int page, int size) throws Exception {
        String response = mvc.perform(get(url(animeId) + "?page=" + page + "&size=" + size))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response).path("data");
    }
}
