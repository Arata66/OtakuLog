package com.otakulog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.config.TasteAiProperties;
import com.otakulog.service.TasteAiService;
import com.otakulog.service.TasteAiTransactions;
import com.otakulog.dto.TasteAiDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:taste_ai;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser
@ExtendWith(OutputCaptureExtension.class)
class TasteAiTest {
    static HttpServer server;
    static volatile String response;
    static volatile String sent;
    static volatile int supplierStatus;
    static volatile Runnable onRequest;
    static AtomicInteger externalCalls = new AtomicInteger();
    @DynamicPropertySource static void 假供应商(DynamicPropertyRegistry properties) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            externalCalls.incrementAndGet();
            if (onRequest != null) onRequest.run();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(supplierStatus, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        properties.add("app.ai.enabled", () -> "true");
        properties.add("app.ai.api-key", () -> "fake-local-key");
        properties.add("app.ai.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        properties.add("app.ai.daily-limit", () -> "2");
    }
    @AfterAll static void 停止假服务() { if (server != null) server.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AnimeRepository anime;
    @Autowired JdbcTemplate jdbc;
    @Autowired TasteAiService service;
    @Autowired TasteAiTransactions transactions;
    @Autowired TasteAiProperties configuration;
    @Autowired PlatformTransactionManager transactionManager;
    long animeId, memoryId;
    @BeforeEach void 准备虚构记忆() throws Exception {
        seed();
        response = completion("{\"insights\":[]}", "stop", true); supplierStatus = 200; sent = null; onRequest = null; externalCalls.set(0);
        configuration.setEnabled(true); configuration.setDailyLimit(2);
        Anime item = new Anime(); item.setName("不得发给模型的虚构作品" + UUID.randomUUID()); animeId = anime.saveAndFlush(item).getId();
        memoryId = data(mvc.perform(post("/api/anime/" + animeId + "/memories").with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("key", UUID.randomUUID().toString()).put("content", "配乐动人，节奏太慢")
                        .put("liked", "音乐动人").put("disliked", "节奏太慢").put("scope", "前两集").toString()))).path("id").asLong();
    }
    void seed() { jdbc.update("DELETE FROM ai_call"); jdbc.update("DELETE FROM ai_quota");
        jdbc.update("INSERT INTO ai_quota (id,quota_date,reserved_calls) VALUES (1,?,0)", LocalDate.now()); }
    @Test void 当预览时应该显示估算而不调用供应商() throws Exception {
        seed(); JsonNode preview = preview(body(), 200);
        assertTrue(preview.path("configured").asBoolean()); assertEquals(17, preview.path("selectedCharacters").asInt());
        assertEquals(1200, preview.path("maxOutputTokens").asInt()); assertTrue(preview.path("estimateOnly").asBoolean());
        assertEquals(0, externalCalls.get()); assertEquals(0, preview.path("callsToday").asInt());
    }
    @Test void 当选择字段省略或显式为空时应该只发送核对后的文字() throws Exception {
        seed(); ObjectNode request = body(); request.putObject("selections").putNull("content").put("liked", "动人").put("disliked", "");
        JsonNode preview = preview(request, 200); assertEquals(2, preview.path("selectedCharacters").asInt());
        assertTrue(preview.path("selections").path("content").isNull());
        extract(request, 200); JsonNode outbound = mapper.readTree(sent);
        assertEquals("deepseek-flash", outbound.path("model").asText()); assertFalse(outbound.path("stream").asBoolean());
        assertEquals("disabled", outbound.path("thinking").path("type").asText()); assertEquals(1200, outbound.path("max_tokens").asInt());
        assertEquals("json_object", outbound.path("response_format").path("type").asText()); assertEquals(2, outbound.path("messages").size());
        String selected = outbound.path("messages").get(1).path("content").asText();
        assertTrue(selected.contains("动人")); assertFalse(selected.contains("配乐动人")); assertFalse(selected.contains("节奏太慢"));
        assertFalse(sent.contains("不得发给模型")); assertTrue(selected.contains("前两集"));
    }
    @Test void 当来源版本选择或严格类型非法时应该拒绝且不占额度() throws Exception {
        seed(); preview(body().put("sourceVersion", 1), 409); preview(body().put("sourceVersion", 0.5), 400);
        preview(body().put("sourceVersion", "0"), 400); preview(body().put("sourceVersion", -1), 400);
        ObjectNode wrong = body(); wrong.putObject("selections").put("content", "不存在的引用"); preview(wrong, 400);
        wrong = body(); wrong.putObject("selections").put("content", 5); preview(wrong, 400);
        wrong = body(); wrong.putObject("selections").putNull("content").putNull("liked").putNull("disliked"); preview(wrong, 400);
        mvc.perform(post(url().replace("/memories/" + memoryId, "/memories/999999") + "/preview").with(csrf())
                .contentType("application/json").content(body().toString())).andExpect(status().isNotFound());
        assertEquals(0, externalCalls.get()); assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class));
    }
    @Test void 当相同请求键重试或空结果有缓存时应该不重复付费() throws Exception {
        seed(); ObjectNode request = body(); JsonNode first = extract(request, 200); assertEquals("SUCCEEDED", first.path("status").asText());
        assertEquals(first.path("requestKey"), extract(request, 200).path("requestKey"));
        JsonNode cached = extract(body(), 200); assertTrue(cached.path("cached").asBoolean()); assertEquals(first.path("requestKey"), cached.path("requestKey"));
        assertTrue(cached.path("entries").isEmpty()); assertEquals(1, externalCalls.get());
        assertEquals(first.path("requestKey"), preview(body(), 200).path("cachedRequestKey"));
        request.putObject("selections").put("content", "配乐").putNull("liked").putNull("disliked"); extract(request, 409);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class));
    }
    @Test void 当候选有效时应该保存原始建议并缓存保留用户纠正拒绝与删除() throws Exception {
        seed(); response = completion(insight("配乐动人"), "stop", true); JsonNode result = extract(body(), 200);
        JsonNode entry = result.path("entries").get(0); assertEquals("AI", entry.path("origin").asText()); assertEquals("PENDING", entry.path("status").asText());
        assertEquals(result.path("requestKey"), entry.path("aiCallKey")); assertEquals(entry.path("quote"), entry.path("suggestedQuote"));
        jdbc.update("UPDATE memory_insight SET statement='用户纠正',status='REJECTED' WHERE id=?", entry.path("id").asLong());
        JsonNode cached = extract(body(), 200); assertEquals("用户纠正", cached.path("entries").get(0).path("statement").asText());
        assertEquals("REJECTED", cached.path("entries").get(0).path("status").asText());
        jdbc.update("DELETE FROM memory_insight WHERE id=?", entry.path("id").asLong()); assertTrue(extract(body(), 200).path("entries").isEmpty());
        assertEquals(1, externalCalls.get());
    }
    @Test void 当供应商失败时应该保留用量和请求键且失败不退额度() throws Exception {
        seed(); supplierStatus = 503; response = "{\"error\":\"不应泄漏的供应商正文\",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";
        ObjectNode first = body(); JsonNode failed = extract(first, 502); assertEquals("FAILED", failed.path("status").asText());
        assertEquals(12, failed.path("usage").path("promptTokens").asInt()); assertTrue(failed.path("message").asText().contains("可能已计费"));
        assertFalse(failed.toString().contains("不应泄漏")); extract(first, 502); assertEquals(1, externalCalls.get());
        JsonNode read = data(mvc.perform(get("/api/taste/ai/calls/" + first.path("requestKey").asText())).andExpect(status().isOk()));
        assertEquals("FAILED", read.path("status").asText()); extract(body(), 502); extract(body(), 409); assertEquals(2, externalCalls.get());
        assertEquals(2, preview(body(), 200).path("callsToday").asInt());
    }
    @Test void 当跨日时应该按今天账本保守重置计数() throws Exception {
        seed(); extract(body(), 200); jdbc.update("UPDATE ai_quota SET quota_date=?,reserved_calls=99 WHERE id=1", LocalDate.now().minusDays(1));
        supplierStatus = 429; response = "{}"; ObjectNode selected = body(); selected.putObject("selections").put("content", "配乐").putNull("liked").putNull("disliked");
        extract(selected, 502); assertEquals(2, jdbc.queryForObject("SELECT reserved_calls FROM ai_quota WHERE id=1", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class));
    }
    @Test void 当来源在请求期间修改或删除时应该保留过期账本而不写候选() throws Exception {
        seed(); response = completion(insight("配乐动人"), "stop", true);
        onRequest = () -> jdbc.update("UPDATE anime_memory SET version=version+1 WHERE id=?", memoryId);
        JsonNode stale = extract(body(), 409); assertEquals("STALE", stale.path("status").asText()); assertTrue(stale.path("entries").isEmpty());
        assertEquals(12, stale.path("usage").path("promptTokens").asInt());
        onRequest = () -> jdbc.update("DELETE FROM anime_memory WHERE id=?", memoryId);
        extract(body().put("sourceVersion", 1), 409); assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call WHERE status='STALE'", Long.class));
    }
    @Test void 当非停止输出时应该拒绝全部候选但保留用量() throws Exception {
        seed(); response = completion(insight("配乐动人"), "length", true); JsonNode failed = extract(body(), 502);
        assertEquals(3, failed.path("usage").path("completionTokens").asInt()); assertTrue(failed.path("entries").isEmpty());
    }
    @Test void 当用量缺失时应该显示未知而不伪造零() throws Exception {
        seed(); response = completion("{\"insights\":[]}", "stop", false); assertTrue(extract(body(), 200).path("usage").isNull());
        JsonNode usage = data(mvc.perform(get("/api/taste/ai/usage")).andExpect(status().isOk())); assertEquals(1, usage.path("unknownCallCount").asInt());
    }
    @Test void 当缺CSRF或匿名时应该拒绝访问() throws Exception {
        mvc.perform(post(url() + "/extract").contentType("application/json").content(body().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/taste/ai/usage").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post(url() + "/preview").with(anonymous()).with(csrf()).contentType("application/json").content(body().toString())).andExpect(status().isUnauthorized());
    }
    @Test void 当请求JSON有重复键或尾随文本时应该拒绝且不占额() throws Exception {
        seed(); for (String request : new String[]{"{\"sourceVersion\":1,\"sourceVersion\":0}", "{\"sourceVersion\":0} {}",
                "{\"sourceVersion\":0,\"selections\":{\"content\":null,\"content\":\"配乐\"}}"})
            mvc.perform(post(url() + "/preview").with(csrf()).contentType("application/json").content(request)).andExpect(status().isBadRequest());
        assertEquals(0, externalCalls.get());
    }
    @Test void 当所选文字超过六千或为空白时应该拒绝而不静默截断() throws Exception {
        seed(); jdbc.update("UPDATE anime_memory SET content=?,liked=NULL,disliked=NULL WHERE id=?", "字".repeat(6001), memoryId);
        preview(body(), 400); ObjectNode selected = body(); selected.putObject("selections").put("content", "字".repeat(6000));
        assertEquals(6000, preview(selected, 200).path("selectedCharacters").asInt());
        jdbc.update("UPDATE anime_memory SET content=? WHERE id=?", "\u00a0\u0085\u001c", memoryId); preview(body(), 400);
        assertEquals(0, externalCalls.get());
    }
    @Test void 当未配置或限额为零时应该仍能预览但禁止付费() throws Exception {
        seed(); configuration.setEnabled(false); assertFalse(preview(body(), 200).path("configured").asBoolean()); extract(body(), 400);
        configuration.setEnabled(true); configuration.setDailyLimit(0); extract(body(), 409);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class)); assertEquals(0, externalCalls.get());
    }
    @Test void 当观点剩余容量不足时应该在付费前拒绝() throws Exception {
        seed(); String sql = "INSERT INTO memory_insight (insight_key,memory_id,source_version,quote_field,quote,statement,direction,aspect,factor,status,origin,version,created_at,updated_at) VALUES (?, ?,0,'CONTENT','配乐','喜欢配乐','LIKE','AUDIOVISUAL','配乐','PENDING','MANUAL',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
        for (int i = 0; i < 195; i++) jdbc.update(sql, UUID.randomUUID().toString(), memoryId);
        extract(body(), 409); assertEquals(0, externalCalls.get()); assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class));
    }
    @Test void 当请求运行中时应该同键读状态而新请求受阻且旧请求永不重发() throws Exception {
        seed(); ObjectNode request = body(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        onRequest = () -> { entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("假服务等待超时"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        TasteAiDTO.Request dto = mapper.treeToValue(request, TasteAiDTO.Request.class);
        CompletableFuture<TasteAiDTO.Result> pending = CompletableFuture.supplyAsync(() -> service.extract(animeId, memoryId, dto));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); JsonNode running = extract(request, 200); assertEquals("RUNNING", running.path("status").asText());
            extract(body(), 409); assertEquals(1, externalCalls.get());
            jdbc.update("UPDATE ai_call SET created_at=DATEADD('SECOND',-121,CURRENT_TIMESTAMP) WHERE request_key=?", dto.requestKey());
            assertTrue(extract(request, 200).path("message").asText().contains("未知")); assertEquals(1, externalCalls.get());
        } finally { release.countDown(); }
        assertEquals(com.otakulog.enums.AiCallStatus.SUCCEEDED, pending.get(5, TimeUnit.SECONDS).status());
    }
    @Test void 当午夜账本存在时应该只归属于当天一个区间() throws Exception {
        seed(); JsonNode first = extract(body(), 200);
        jdbc.update("UPDATE ai_call SET created_at=? WHERE request_key=?", LocalDate.now().atStartOfDay(), first.path("requestKey").asText());
        assertEquals(1, preview(body(), 200).path("callsToday").asInt());
        jdbc.update("UPDATE ai_quota SET quota_date=?,reserved_calls=0 WHERE id=1", LocalDate.now().minusDays(1));
        assertEquals(1, preview(body(), 200).path("callsToday").asInt());
    }
    @Test void 当非法JSON带私人文字时应该不输出到日志(CapturedOutput output) throws Exception {
        String sensitive = "不得记录的虚构隐私感想";
        mvc.perform(post(url() + "/preview").with(csrf()).contentType("application/json")
                .content("{\"sourceVersion\":\"" + sensitive + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sensitive))));
        assertFalse(output.getAll().contains(sensitive)); assertFalse(output.getAll().contains("fake-local-key"));
    }
    @Test void 当调用方已有事务时应该独立预留并在外部请求前释放锁() throws Exception {
        onRequest = () -> assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call WHERE status='RUNNING'", Long.class));
        TasteAiDTO.Request request = mapper.treeToValue(body(), TasteAiDTO.Request.class);
        TasteAiDTO.Result result = new TransactionTemplate(transactionManager).execute(transaction -> {
            TasteAiDTO.Result value = service.extract(animeId, memoryId, request); transaction.setRollbackOnly(); return value;
        });
        assertEquals(com.otakulog.enums.AiCallStatus.SUCCEEDED, result.status());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class)); assertEquals(1, externalCalls.get());
    }
    @Test void 当预算种子不存在时应该安全拒绝而不自动建表或初始化() throws Exception {
        jdbc.update("DELETE FROM ai_quota"); extract(body(), 409); assertEquals(0, externalCalls.get());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_quota", Long.class));
    }
    @Test void 当运行中账本时间在未来时应该显示未知且同键不再外发() throws Exception {
        ObjectNode request = body(); TasteAiDTO.Request dto = mapper.treeToValue(request, TasteAiDTO.Request.class);
        transactions.reserve(animeId, memoryId, dto);
        jdbc.update("UPDATE ai_call SET created_at=? WHERE request_key=?", LocalDate.now().plusDays(1).atStartOfDay(), dto.requestKey());
        JsonNode read = data(mvc.perform(get("/api/taste/ai/calls/" + dto.requestKey())).andExpect(status().isOk()));
        assertTrue(read.path("message").asText().contains("未知"));
        assertTrue(extract(request, 200).path("message").asText().contains("未知")); assertEquals(0, externalCalls.get());
    }
    @Test void 当未来运行中账本存在时应该允许新键正常整理() throws Exception {
        TasteAiDTO.Request dto = mapper.treeToValue(body(), TasteAiDTO.Request.class);
        transactions.reserve(animeId, memoryId, dto);
        jdbc.update("UPDATE ai_call SET created_at=? WHERE request_key=?", LocalDate.now().plusDays(1).atStartOfDay(), dto.requestKey());
        assertEquals("SUCCEEDED", extract(body(), 200).path("status").asText()); assertEquals(1, externalCalls.get());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call", Long.class));
    }
    @Test void 当第二条候选实际落库失败时应该全部回滚且补偿保存已知用量() throws Exception {
        jdbc.execute("ALTER TABLE memory_insight ADD CONSTRAINT ck_test_ai_flush_failure CHECK (origin <> 'AI' OR aspect <> 'OTHER')");
        try {
            ObjectNode output = (ObjectNode) mapper.readTree(insight("配乐动人"));
            output.withArray("insights").add(output.path("insights").get(0).deepCopy());
            ((ObjectNode) output.path("insights").get(1)).put("aspect", "OTHER");
            response = completion(output.toString(), "stop", true); ObjectNode request = body(); JsonNode failed = extract(request, 502);
            assertEquals("FAILED", failed.path("status").asText()); assertEquals(12, failed.path("usage").path("promptTokens").asInt());
            assertEquals(3, failed.path("usage").path("completionTokens").asInt()); assertTrue(failed.path("entries").isEmpty());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM memory_insight WHERE memory_id=?", Long.class, memoryId));
            assertEquals("FAILED", data(mvc.perform(get("/api/taste/ai/calls/" + request.path("requestKey").asText()))
                    .andExpect(status().isOk())).path("status").asText());
            extract(request, 502); assertEquals(1, externalCalls.get());
            assertEquals(1, jdbc.queryForObject("SELECT reserved_calls FROM ai_quota WHERE id=1", Integer.class));
        } finally { jdbc.execute("ALTER TABLE memory_insight DROP CONSTRAINT ck_test_ai_flush_failure"); }
    }
    @Test void 当运行中账本仅为恢复历史时应该显示未知不阻新键且同键不外发() throws Exception {
        ObjectNode request = body(); TasteAiDTO.Request dto = mapper.treeToValue(request, TasteAiDTO.Request.class);
        transactions.reserve(animeId, memoryId, dto);
        jdbc.update("UPDATE ai_call SET history_only=TRUE WHERE request_key=?", dto.requestKey());
        assertTrue(data(mvc.perform(get("/api/taste/ai/calls/" + dto.requestKey())).andExpect(status().isOk()))
                .path("message").asText().contains("未知"));
        assertEquals("RUNNING", extract(request, 200).path("status").asText()); assertEquals(0, externalCalls.get());
        assertEquals("SUCCEEDED", extract(body(), 200).path("status").asText()); assertEquals(1, externalCalls.get());
        assertFalse(jdbc.queryForObject("SELECT history_only FROM ai_call WHERE status='SUCCEEDED'", Boolean.class));
    }
    ObjectNode body() { return mapper.createObjectNode().put("sourceVersion", 0).put("requestKey", UUID.randomUUID().toString()); }
    String url() { return "/api/anime/" + animeId + "/memories/" + memoryId + "/insights/ai"; }
    JsonNode preview(ObjectNode request, int status) throws Exception { return data(mvc.perform(post(url() + "/preview").with(csrf()).contentType("application/json")
            .content(request.toString())).andExpect(status().is(status))); }
    JsonNode extract(ObjectNode request, int status) throws Exception { return data(mvc.perform(post(url() + "/extract").with(csrf()).contentType("application/json")
            .content(request.toString())).andExpect(status().is(status))); }
    JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"); }
    static String completion(String content, String finish, boolean usage) { try {
        ObjectMapper json = new ObjectMapper(); ObjectNode result = json.createObjectNode();
        result.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("content", content);
        if (usage) result.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 3);
        return result.toString();
    } catch (Exception e) { throw new IllegalStateException("虚构响应准备失败"); } }
    static String insight(String quote) { return "{\"insights\":[{\"quoteField\":\"CONTENT\",\"quote\":\"" + quote + "\",\"statement\":\"我喜欢配乐\",\"direction\":\"LIKE\",\"aspect\":\"AUDIOVISUAL\",\"factor\":\"配乐\"}]}"; }
}
