package com.otakulog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:taste_profile;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.session.events.log=false"})
@AutoConfigureMockMvc
@WithMockUser
class TasteProfileTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AnimeRepository anime;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @BeforeEach void 清理独立测试库() {
        jdbc.update("DELETE FROM memory_insight"); jdbc.update("DELETE FROM anime_memory"); anime.deleteAll();
    }

    @Test void 当没有确认依据时应该返回空画像() throws Exception {
        JsonNode profile = profile();
        assertEquals(0, profile.path("animeCount").asInt());
        assertEquals(0, profile.path("evidenceCount").asInt());
        assertEquals(0, profile.path("staleCount").asInt());
        assertEquals(0, profile.path("groups").size());
    }
    @Test void 当同作品多次回望与正负并存时应该精确分组去重且保留证据() throws Exception {
        long first = anime("虚构作品甲"), second = anime("虚构作品乙");
        long initial = memory(first), reflection = memory(first), other = memory(second);
        insight(first, initial, " ＭＵＳＩＣ　编排 ", "LIKE", "CONFIRMED");
        insight(first, reflection, "music  编排", "DISLIKE", "CONFIRMED");
        insight(second, other, "Music 编排", "LIKE", "CONFIRMED");
        insight(second, other, "配乐", "LIKE", "CONFIRMED");
        insight(second, other, "拒绝因素", "LIKE", "REJECTED");
        insight(second, other, "候选因素", "LIKE", "PENDING");
        JsonNode profile = profile();
        assertEquals(2, profile.path("animeCount").asInt());
        assertEquals(4, profile.path("evidenceCount").asInt());
        assertEquals(2, profile.path("groups").size());
        JsonNode group = profile.path("groups").get(0);
        assertEquals(" ＭＵＳＩＣ　编排 ", group.path("factor").asText());
        assertEquals(2, group.path("animeCount").asInt());
        assertEquals(2, group.path("likes").size()); assertEquals(1, group.path("dislikes").size());
        JsonNode evidence = group.path("likes").get(0);
        assertEquals(first, evidence.path("animeId").asLong()); assertEquals("虚构作品甲", evidence.path("animeName").asText());
        assertEquals(initial, evidence.path("memoryId").asLong()); assertEquals("REFLECTION", evidence.path("context").asText());
        assertEquals("第一季", evidence.path("scope").asText()); assertEquals("配乐动人", evidence.path("quote").asText());
        assertFalse(evidence.path("memoryKey").asText().isBlank()); assertFalse(evidence.path("insightKey").asText().isBlank());
        assertFalse(evidence.path("sourceCreatedAt").isNull());
        assertEquals(profile, profile());
    }
    @Test void 当记忆编辑导致失效时应该排除旧依据并单列失效数() throws Exception {
        long id = anime("虚构作品"), memory = memory(id);
        insight(id, memory, "配乐", "LIKE", "CONFIRMED");
        mvc.perform(put("/api/anime/" + id + "/memories/" + memory).with(csrf()).contentType("application/json")
                .content("{\"content\":\"更换感想\",\"expectedVersion\":0}")).andExpect(status().isOk());
        JsonNode profile = profile();
        assertEquals(0, profile.path("evidenceCount").asInt()); assertEquals(0, profile.path("animeCount").asInt());
        assertEquals(1, profile.path("staleCount").asInt()); assertEquals(0, profile.path("groups").size());
    }
    @Test void 当因素首尾含NEL空白时应该与普通因素归为同组且保留显示原文() throws Exception {
        long id = anime("虚构Unicode因素作品"), memory = memory(id);
        insight(id, memory, "\u0085配乐\u0085", "LIKE", "CONFIRMED");
        insight(id, memory, "配乐", "DISLIKE", "CONFIRMED");
        JsonNode profile = profile();
        assertEquals(1, profile.path("groups").size());
        JsonNode group = profile.path("groups").get(0);
        assertEquals("\u0085配乐\u0085", group.path("factor").asText());
        assertEquals(1, group.path("likes").size()); assertEquals(1, group.path("dislikes").size());
        assertEquals(1, group.path("animeCount").asInt()); assertEquals(2, profile.path("evidenceCount").asInt());
    }
    @Test void 当因素内部混合NEL与Java空白时应该归一为同组() throws Exception {
        long id = anime("虚构混合空白作品"), memory = memory(id);
        insight(id, memory, "配\u0085\u001c乐", "LIKE", "CONFIRMED");
        insight(id, memory, "配 乐", "DISLIKE", "CONFIRMED");
        JsonNode profile = profile();
        assertEquals(1, profile.path("groups").size());
        JsonNode group = profile.path("groups").get(0);
        assertEquals("配\u0085\u001c乐", group.path("factor").asText());
        assertEquals(1, group.path("likes").size()); assertEquals(1, group.path("dislikes").size());
    }
    @Test void 当画像涵盖更多作品时应该保持读取查询次数不增长() throws Exception {
        long first = anime("虚构单作品查询"), firstMemory = memory(first);
        insight(first, firstMemory, "配乐", "LIKE", "CONFIRMED");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(1, profile().path("animeCount").asInt());
        long singleQueries = statistics.getPrepareStatementCount();
        for (int i = 0; i < 3; i++) {
            long id = anime("虚构多作品查询" + i), memory = memory(id);
            insight(id, memory, "配乐", "LIKE", "CONFIRMED");
        }
        statistics.clear();
        assertEquals(4, profile().path("animeCount").asInt());
        long multipleQueries = statistics.getPrepareStatementCount();
        assertTrue(singleQueries > 0);
        assertEquals(singleQueries, multipleQueries, "读取查询次数不能随作品条目数增长");
    }
    @Test void 当相同因素属于不同方面时应该分别成组() throws Exception {
        long id = anime("虚构不同方面作品"), memory = memory(id);
        insight(id, memory, "复杂", "LIKE", "CONFIRMED", "NARRATIVE");
        insight(id, memory, "复杂", "LIKE", "CONFIRMED", "CHARACTER");
        JsonNode profile = profile();
        assertEquals(2, profile.path("groups").size());
        assertEquals("NARRATIVE", profile.path("groups").get(0).path("aspect").asText());
        assertEquals("CHARACTER", profile.path("groups").get(1).path("aspect").asText());
        assertEquals(1, profile.path("animeCount").asInt()); assertEquals(2, profile.path("evidenceCount").asInt());
    }
    private long anime(String name) { Anime value = new Anime(); value.setName(name); return anime.saveAndFlush(value).getId(); }
    private long memory(long id) throws Exception {
        return data(mvc.perform(post("/api/anime/" + id + "/memories").with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("key", UUID.randomUUID().toString()).put("content", "配乐动人")
                        .put("context", "REFLECTION").put("scope", "第一季").toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();
    }
    private void insight(long id, long memory, String factor, String direction, String status) throws Exception {
        insight(id, memory, factor, direction, status, "AUDIOVISUAL");
    }
    private void insight(long id, long memory, String factor, String direction, String status, String aspect) throws Exception {
        ObjectNode request = mapper.createObjectNode().put("key", UUID.randomUUID().toString()).put("sourceVersion", 0)
                .put("quoteField", "CONTENT").put("quote", "配乐动人").put("statement", "个人观点").put("direction", direction)
                .put("aspect", aspect).put("factor", factor).put("status", status);
        mvc.perform(post("/api/anime/" + id + "/memories/" + memory + "/insights").with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isOk());
    }
    private JsonNode profile() throws Exception { return data(mvc.perform(get("/api/taste/profile")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private JsonNode data(String response) throws Exception { return mapper.readTree(response).path("data"); }
}
