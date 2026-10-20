package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.entity.Anime;
import com.otakulog.entity.AnimeMemory;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.enums.MemoryContext;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.AnimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:memory_review;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class MemoryReviewTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnimeRepository anime;
    @Autowired private AnimeMemoryRepository memories;
    private Anime first, second;

    @BeforeEach
    void 准备隔离记录() {
        memories.deleteAll();
        first = work("虚构旅人"); second = work("虚构海岸");
    }

    @Test
    void 当跨作品回顾时应该保留原文并联合筛选作品和语境() throws Exception {
        entry(first, "  原文\n换行  ", MemoryContext.INITIAL, "2026-10-01T10:00:00");
        var target = entry(second, "回望音乐", MemoryContext.REFLECTION, "2026-10-02T10:00:00");
        var all = result("/api/memories");
        assertEquals(2, all.path("totalEntries").asLong());
        assertEquals("  原文\n换行  ", all.path("entries").get(1).path("memory").path("content").asText());
        assertEquals(2, all.path("works").size());
        var filtered = result("/api/memories?animeId=" + second.getId() + "&context=REFLECTION");
        assertEquals(1, filtered.path("totalEntries").asLong());
        assertEquals(target.getId(), filtered.path("entries").get(0).path("memory").path("id").asLong());
        assertEquals(second.getName(), filtered.path("entries").get(0).path("animeName").asText());
    }

    @Test
    void 当按月份回顾时应该只按书写时间且不补观看日期() throws Exception {
        var target = entry(first, "十月书写", MemoryContext.NOTE, "2026-10-01T00:00:00");
        entry(first, "九月末", MemoryContext.NOTE, "2026-09-30T23:59:59.999999");
        entry(first, "十一月初", MemoryContext.NOTE, "2026-11-01T00:00:00");
        var result = result("/api/memories?month=2026-10");
        assertEquals(1, result.path("totalEntries").asLong());
        assertEquals(target.getId(), result.path("entries").get(0).path("memory").path("id").asLong());
        assertTrue(result.path("entries").get(0).path("memory").path("watchedDate").isNull());
        target.setWatchedDate(LocalDate.of(2020, 1, 1)); memories.saveAndFlush(target);
        assertEquals(0, result("/api/memories?month=2020-01").path("totalEntries").asLong());
    }

    @Test
    void 当同一时刻存在多条感想时应该按编号稳定排序并支持正序() throws Exception {
        var a = entry(first, "先写", MemoryContext.INITIAL, "2026-10-01T00:00:00");
        var b = entry(second, "后写", MemoryContext.REWATCH, "2026-10-01T00:00:00");
        assertEquals(b.getId(), result("/api/memories").path("entries").get(0).path("memory").path("id").asLong());
        assertEquals(a.getId(), result("/api/memories?order=oldest").path("entries").get(0).path("memory").path("id").asLong());
    }

    @Test
    void 当回到超过一页的旧感想时应该定位原作品和准确页码() throws Exception {
        var target = entry(first, "旧感想", MemoryContext.INITIAL, "2026-10-01T00:00:00");
        for (int i = 0; i < 21; i++) entry(first, "新感想" + i, MemoryContext.NOTE, "2026-10-02T00:00:00");
        var found = result("/api/memories/" + target.getId());
        assertEquals(first.getId(), found.path("anime").path("id").asLong());
        assertEquals("watching", found.path("anime").path("status").asText());
        assertEquals(target.getId(), found.path("memoryId").asLong());
        assertEquals(1, found.path("page").asInt());
        assertEquals(20, result("/api/memories").path("entries").size());
        assertEquals(2, result("/api/memories?page=1").path("entries").size());
    }

    @Test
    void 当页码极大或筛选无结果时应该返回空页而不溢出() throws Exception {
        entry(first, "感想", MemoryContext.NOTE, "2026-10-01T00:00:00");
        assertEquals(0, result("/api/memories?page=2147483647").path("entries").size());
        assertEquals(0, result("/api/memories?animeId=" + second.getId()).path("totalEntries").asLong());
    }

    @Test
    void 当条件非法或目标已删除时应该明确拒绝() throws Exception {
        for (String query : new String[]{"page=-1", "month=2026-1", "month=2026-13", "month=0999-12", "context=other", "order=other", "animeId=-1"})
            mvc.perform(get("/api/memories?" + query)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/memories/99999999")).andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void 当未登录时应该拒绝读取跨作品原文() throws Exception {
        mvc.perform(get("/api/memories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/memories/1")).andExpect(status().isUnauthorized());
    }

    private JsonNode result(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    private Anime work(String name) {
        var value = new Anime(); value.setName(name); value.setStatus(AnimeStatus.WATCHING);
        value.setCurrentEpisode(1); value.setTotalEpisodes(12); value.setScore(8.0); value.setSeason("虚构季度");
        return anime.saveAndFlush(value);
    }
    private AnimeMemory entry(Anime work, String text, MemoryContext context, String created) {
        var value = new AnimeMemory(); value.setAnimeId(work.getId()); value.setMemoryKey(UUID.randomUUID().toString());
        value.setContent(text); value.setContext(context); value.setCreatedAt(LocalDateTime.parse(created));
        value.setUpdatedAt(LocalDateTime.parse(created)); return memories.saveAndFlush(value);
    }
}
