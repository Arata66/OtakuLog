package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:memory_insight;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
class MemoryInsightTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AnimeRepository anime;
    @Autowired JdbcTemplate jdbc;
    long animeId, memoryId;

    @BeforeEach void 准备虚构记忆() throws Exception {
        Anime value = new Anime(); value.setName("虚构观点验收" + UUID.randomUUID());
        animeId = anime.saveAndFlush(value).getId();
        memoryId = data(mvc.perform(post("/api/anime/" + animeId + "/memories").with(csrf()).with(user("test"))
                .contentType("application/json").content(mapper.createObjectNode().put("key", UUID.randomUUID().toString())
                        .put("content", "配乐动人，节奏太慢").put("liked", "音乐动人").toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();
    }

    @Test void 当手工确认观点时应该保留来源和原始建议为空() throws Exception {
        JsonNode entry = create(body(), 200);
        assertEquals("MANUAL", entry.path("origin").asText());
        assertTrue(entry.path("suggestedStatement").isNull());
        assertTrue(entry.path("suggestedQuoteField").isNull());
        assertTrue(entry.path("suggestedQuote").isNull());
        assertFalse(entry.path("stale").asBoolean());
        JsonNode page = data(mvc.perform(get(url())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(memoryId, page.path("memoryId").asLong());
        assertEquals(0, page.path("sourceVersion").asLong());
        assertEquals(entry, page.path("entries").get(0));
    }
    @Test void 当按记忆编号定位依据时应该读取当前原文并校验作品归属() throws Exception {
        String memoryUrl = "/api/anime/" + animeId + "/memories/" + memoryId;
        JsonNode memory = data(mvc.perform(get(memoryUrl)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(memoryId, memory.path("id").asLong());
        assertEquals("配乐动人，节奏太慢", memory.path("content").asText());
        mvc.perform(put(memoryUrl).with(csrf()).contentType("application/json")
                .content("{\"content\":\"核对后的当前原文\",\"expectedVersion\":0}")).andExpect(status().isOk());
        JsonNode current = data(mvc.perform(get(memoryUrl)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, current.path("version").asLong()); assertEquals("核对后的当前原文", current.path("content").asText());
        Anime other = new Anime(); other.setName("虚构其他作品" + UUID.randomUUID()); long otherId = anime.saveAndFlush(other).getId();
        mvc.perform(get("/api/anime/" + otherId + "/memories/" + memoryId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/anime/" + animeId + "/memories/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    }
    @Test void 当相同UUID重试时应该幂等而内容或归属变化应该冲突() throws Exception {
        ObjectNode request = body(); JsonNode first = create(request, 200);
        assertEquals(first, create(request, 200));
        create(request.deepCopy().put("statement", "修改陈述"), 409);
        mvc.perform(post(url().replace("/memories/" + memoryId, "/memories/" + Long.MAX_VALUE)).with(csrf())
                .contentType("application/json").content(request.toString())).andExpect(status().isNotFound());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
        准备虚构记忆();
        create(request, 409);
    }
    @Test void 当达到两百条观点时应该拒绝新增但允许同键重试和编辑() throws Exception {
        ObjectNode request = body(); JsonNode first = create(request, 200);
        for (int i = 1; i < 200; i++) create(body(), 200);
        create(body(), 400);
        assertEquals(first, create(request, 200));
        ObjectNode edit = request.deepCopy().put("expectedVersion", 0);
        assertEquals(first, edit(first, edit, 200));
        assertEquals(200L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
    }
    @Test void 当编辑模型候选时应该保留调用归属和原始建议() throws Exception {
        ObjectNode request = body(); JsonNode first = create(request, 200);
        String callKey = UUID.randomUUID().toString();
        jdbc.update("UPDATE memory_insight SET origin='AI', ai_call_key=?, suggested_quote_field='CONTENT', suggested_quote='配乐动人', suggested_statement=?, suggested_direction='LIKE', suggested_aspect='AUDIOVISUAL', suggested_factor=? WHERE id=?",
                callKey, "原始建议", "原始因素", first.path("id").asLong());
        JsonNode edited = edit(first, request.deepCopy().put("expectedVersion", 0).put("statement", "用户纠正").put("quote", "动人")
                .put("origin", "MANUAL").put("aiCallKey", UUID.randomUUID().toString()).put("suggestedStatement", "不应覆盖")
                .put("suggestedQuoteField", "LIKED").put("suggestedQuote", "不应覆盖"), 200);
        assertEquals("用户纠正", edited.path("statement").asText());
        assertEquals("AI", edited.path("origin").asText()); assertEquals(callKey, edited.path("aiCallKey").asText());
        assertEquals("原始建议", edited.path("suggestedStatement").asText());
        assertEquals("CONTENT", edited.path("suggestedQuoteField").asText());
        assertEquals("配乐动人", edited.path("suggestedQuote").asText());
        assertEquals("动人", edited.path("quote").asText());
        assertEquals("LIKE", edited.path("suggestedDirection").asText());
        assertEquals("AUDIOVISUAL", edited.path("suggestedAspect").asText());
        assertEquals("原始因素", edited.path("suggestedFactor").asText());
    }
    @Test void 当双窗口修改或删除时应该拒绝旧版本并保留审计时间() throws Exception {
        ObjectNode request = body(); JsonNode first = create(request, 200);
        JsonNode changed = edit(first, request.deepCopy().put("expectedVersion", 0).put("statement", "配乐让我投入"), 200);
        assertEquals(1, changed.path("version").asLong());
        assertEquals(first.path("createdAt"), changed.path("createdAt"));
        assertNotEquals(first.path("updatedAt"), changed.path("updatedAt"));
        edit(first, request.deepCopy().put("expectedVersion", 0), 409);
        mvc.perform(delete(url() + "/" + first.path("id").asLong() + "?expectedVersion=0").with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete(url() + "/" + first.path("id").asLong() + "?expectedVersion=1").with(csrf())).andExpect(status().isOk());
    }
    @Test void 当引用不存在或字段为空时应该拒绝写入() throws Exception {
        create(body().put("quote", "编造理由"), 400);
        create(body().put("quoteField", "DISLIKED"), 400);
        for (String field : new String[]{"quote", "statement", "factor"}) create(body().put(field, " \n "), 400);
        create(body().put("quote", "字".repeat(801)), 400);
        create(body().put("statement", "字".repeat(501)), 400);
        create(body().put("factor", "字".repeat(101)), 400);
        create(body().put("quoteField", "LIKED").put("quote", "音乐动人").put("statement", "字".repeat(500)).put("factor", "字".repeat(100)), 200);
    }
    @ParameterizedTest
    @MethodSource("纯Unicode空白字段")
    void 当引用观点或因素仅为Unicode空白时应该拒绝且不写入(String field, String blank) throws Exception {
        mvc.perform(put("/api/anime/" + animeId + "/memories/" + memoryId).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("content", "\u00a0\u0085\u001c\u001d\u001e\u001f配乐动人").put("expectedVersion", 0).toString()))
                .andExpect(status().isOk());
        create(body().put("sourceVersion", 1).put(field, blank), 400);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
    }
    private static Stream<Arguments> 纯Unicode空白字段() {
        return Stream.of("quote", "statement", "factor")
                .flatMap(field -> Stream.of("\u00a0", "\u0085", "\u001c", "\u001d", "\u001e", "\u001f", "\u0085\u001c")
                        .map(blank -> Arguments.of(field, blank)));
    }
    @Test void 当数字版本或文字枚举类型非法时应该拒绝隐式转换() throws Exception {
        create(body().put("sourceVersion", 0.5), 400);
        create(body().put("sourceVersion", "0"), 400);
        create(body().put("sourceVersion", -1), 400);
        for (String field : new String[]{"key", "quote", "statement", "factor", "direction", "aspect", "status", "quoteField"}) {
            create(body().put(field, 0), 400);
            ObjectNode request = body(); request.putArray(field).add("LIKE"); create(request, 400);
        }
        create(body().put("key", "INVALID"), 400);
        ObjectNode missing = body(); missing.remove("sourceVersion"); create(missing, 400);
        JsonNode first = create(body(), 200);
        edit(first, body().put("expectedVersion", 0.9), 400);
        edit(first, body().put("expectedVersion", "0"), 400);
        edit(first, body().put("expectedVersion", 0), 400);
        mvc.perform(delete(url() + "/" + first.path("id").asLong() + "?expectedVersion=0.5").with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void 当来源修改时应该动态失效并允许显式核对新版本() throws Exception {
        ObjectNode request = body(); JsonNode first = create(request, 200);
        mvc.perform(put("/api/anime/" + animeId + "/memories/" + memoryId).with(csrf()).contentType("application/json")
                .content("{\"content\":\"配乐动人，新感受\",\"expectedVersion\":0}")).andExpect(status().isOk());
        JsonNode page = data(mvc.perform(get(url())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(page.path("entries").get(0).path("stale").asBoolean());
        create(body(), 409);
        edit(first, request.deepCopy().put("expectedVersion", 0), 409);
        assertFalse(edit(first, request.deepCopy().put("sourceVersion", 1).put("expectedVersion", 0), 200).path("stale").asBoolean());
    }
    @Test void 当归属错误时应该返回未找到() throws Exception {
        JsonNode first = create(body(), 200);
        mvc.perform(get("/api/anime/" + Long.MAX_VALUE + "/memories/" + memoryId + "/insights")).andExpect(status().isNotFound());
        edit(mapper.createObjectNode().put("id", Long.MAX_VALUE), body().put("expectedVersion", 0), 404);
        mvc.perform(delete(url() + "/" + Long.MAX_VALUE + "?expectedVersion=0").with(csrf())).andExpect(status().isNotFound());
        准备虚构记忆();
        edit(first, body().put("expectedVersion", 0), 404);
        mvc.perform(delete(url() + "/" + first.path("id").asLong() + "?expectedVersion=0").with(csrf())).andExpect(status().isNotFound());
    }
    @Test void 当删除记忆或作品时应该同步清理观点() throws Exception {
        create(body(), 200);
        mvc.perform(delete("/api/anime/" + animeId + "/memories/" + memoryId + "?expectedVersion=0").with(csrf())).andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
        准备虚构记忆(); create(body(), 200);
        mvc.perform(delete("/api/anime/" + animeId).with(csrf())).andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
        准备虚构记忆(); create(body(), 200);
        mvc.perform(post("/api/anime/batch-delete").with(csrf()).contentType("application/json").content("{\"ids\":[" + animeId + "]}")).andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
    }
    @Test @WithAnonymousUser void 当匿名读取或写入时应该需要登录() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/anime/" + animeId + "/memories/" + memoryId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/taste/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post(url()).with(csrf()).contentType("application/json").content(body().toString())).andExpect(status().isUnauthorized());
    }
    @Test void 当缺少CSRF时应该拒绝观点写入() throws Exception {
        mvc.perform(post(url()).contentType("application/json").content(body().toString())).andExpect(status().isForbidden());
        mvc.perform(put(url() + "/1").contentType("application/json").content(body().toString())).andExpect(status().isForbidden());
        mvc.perform(delete(url() + "/1?expectedVersion=0")).andExpect(status().isForbidden());
    }
    private String url() { return "/api/anime/" + animeId + "/memories/" + memoryId + "/insights"; }
    private ObjectNode body() { return mapper.createObjectNode().put("key", UUID.randomUUID().toString()).put("sourceVersion", 0)
            .put("quoteField", "CONTENT").put("quote", "配乐动人").put("statement", "我喜欢动人的配乐").put("direction", "LIKE")
            .put("aspect", "AUDIOVISUAL").put("factor", "配乐").put("status", "CONFIRMED"); }
    private JsonNode create(ObjectNode body, int code) throws Exception { return data(mvc.perform(post(url()).with(csrf()).contentType("application/json")
            .content(body.toString())).andExpect(status().is(code)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private JsonNode edit(JsonNode first, ObjectNode body, int code) throws Exception { return data(mvc.perform(put(url() + "/" + first.path("id").asLong())
            .with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().is(code)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private JsonNode data(String response) throws Exception { return mapper.readTree(response).path("data"); }
}
