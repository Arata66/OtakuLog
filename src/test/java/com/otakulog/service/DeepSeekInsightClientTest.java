package com.otakulog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.config.TasteAiProperties;
import com.otakulog.dto.TasteAiDTO.*;
import com.otakulog.enums.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekInsightClientTest {
    HttpServer server;
    String response, sent;
    int status = 200, delay, bodyDelay;
    AtomicInteger calls = new AtomicInteger();
    TasteAiProperties properties;
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void 准备回环假服务() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet(); sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { if (delay > 0) Thread.sleep(delay); byte[] data = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, data.length); if (bodyDelay > 0) Thread.sleep(bodyDelay); exchange.getResponseBody().write(data);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start(); properties = new TasteAiProperties(); properties.setEnabled(true); properties.setApiKey("fake-local-key");
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"); properties.setResponseTimeoutMs(1000);
        response = completion("{\"insights\":[]}", "stop", "{\"prompt_tokens\":12,\"completion_tokens\":3}");
    }
    @AfterEach void 关闭假服务() { server.stop(0); }
    Input input() { return new Input(UUID.randomUUID().toString(), 0L, Map.of("content", "配乐动人"), "前两集", MemoryContext.NOTE, "fake", 4); }
    ProviderResult extract() { return new DeepSeekInsightClient(properties).extract(input()); }
    @Test void 当发送时应该只发所选文字且禁用思考与流式响应() throws Exception {
        assertTrue(extract().succeeded()); var json = mapper.readTree(sent); assertEquals("deepseek-flash", json.path("model").asText());
        assertEquals("disabled", json.path("thinking").path("type").asText()); assertEquals(1200, json.path("max_tokens").asInt());
        assertFalse(json.path("stream").asBoolean()); assertEquals("json_object", json.path("response_format").path("type").asText());
        assertEquals(2, json.path("messages").size()); assertFalse(sent.contains("memoryKey")); assertFalse(sent.contains("sourceVersion"));
    }
    @ParameterizedTest @MethodSource("非法候选")
    void 当候选JSON引用类型或长度非法时应该整批失败(String content) throws Exception {
        response = completion(content, "stop", "{\"prompt_tokens\":12,\"completion_tokens\":3}"); ProviderResult value = extract();
        assertFalse(value.succeeded()); assertTrue(value.candidates().isEmpty()); assertEquals(12, value.usage().promptTokens()); assertEquals(1, calls.get());
    }
    static Stream<String> 非法候选() {
        String one = "{\"quoteField\":\"CONTENT\",\"quote\":\"配乐动人\",\"statement\":\"喜欢配乐\",\"direction\":\"LIKE\",\"aspect\":\"AUDIOVISUAL\",\"factor\":\"配乐\"}";
        return Stream.of("{\"insights\":[" + one.replace("配乐动人", "伪造引用") + "]}",
                "{\"insights\":[" + String.join(",", Collections.nCopies(7, one)) + "]}",
                "{\"insights\":[" + one.replace("\"LIKE\"", "0") + "]}", "{\"insights\":[]} {}",
                "{\"insights\":[],\"insights\":[]}", "{\"insights\":[" + one.replace("\"factor\":\"配乐\"", "\"factor\":\"配乐\",\"factor\":\"其他\"") + "]}",
                "{\"insights\":[" + one.replace("喜欢配乐", "字".repeat(501)) + "]}",
                "{\"insights\":[" + one.replace("喜欢配乐", "\u00a0\u0085\u001c") + "]}",
                "{\"insights\":[" + one.replace("AUDIOVISUAL", "NEW") + "]}", "{\"insights\":null}",
                "{\"insights\":[" + one + "," + one.replace("配乐动人", "伪造引用") + "]}");
    }
    @Test void 当供应商限流失败或超时时应该不重试且不泄漏正文() throws Exception {
        for (int code : new int[]{503, 429}) { calls.set(0); status = code; response = "{\"error\":\"秘密供应商正文\",\"usage\":{\"prompt_tokens\":4}}";
            ProviderResult failed = extract(); assertFalse(failed.succeeded()); assertEquals(4, failed.usage().promptTokens());
            assertEquals(1, calls.get()); assertFalse(failed.toString().contains("秘密供应商正文")); }
        calls.set(0); status = 200; delay = 400; properties.setResponseTimeoutMs(50); ProviderResult failed = extract();
        assertEquals(AiErrorCategory.TIMEOUT, failed.errorCategory()); assertEquals(1, calls.get());
    }
    @Test void 当达到输出上限时应该保留真实用量并拒绝候选() throws Exception {
        response = completion("{\"insights\":[]}", "length", "{\"prompt_tokens\":12,\"completion_tokens\":1200}");
        ProviderResult failed = extract(); assertEquals(AiErrorCategory.OUTPUT_LIMIT, failed.errorCategory()); assertEquals(1200, failed.usage().completionTokens());
    }
    @Test void 当usage缺失非法或使用两代缓存字段时应该保留每个可得合法整数() throws Exception {
        response = completion("{\"insights\":[]}", "stop", null); assertNull(extract().usage());
        response = completion("{\"insights\":[]}", "stop", "{\"prompt_tokens\":-1,\"completion_tokens\":\"3\",\"prompt_cache_hit_tokens\":0}");
        Usage partial = extract().usage(); assertNull(partial.promptTokens()); assertNull(partial.completionTokens()); assertEquals(0, partial.cachedTokens());
        response = completion("{\"insights\":[]}", "stop", "{\"prompt_tokens\":8,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":4},\"completion_tokens_details\":{\"reasoning_tokens\":0}}");
        Usage modern = extract().usage(); assertEquals(4, modern.cachedTokens()); assertEquals(0, modern.reasoningTokens());
    }
    @Test void 当服务地址不是生产或回环时应该拒绝配置() {
        for (String endpoint : new String[]{"https://evil.example/chat", "http://127.0.0.1@evil.example/chat", "http://localhost/chat?token=x", "http://localhost/chat#x"}) {
            properties.setEndpoint(endpoint); assertThrows(IllegalArgumentException.class, properties::validate);
        }
        properties.setEndpoint("http://[::1]:1234/chat/completions"); assertDoesNotThrow(properties::validate);
    }
    @Test void 当响应体过大或重定向时应该停止并不再外发() throws Exception {
        response = "字".repeat(300000); assertFalse(extract().succeeded()); assertEquals(1, calls.get());
        calls.set(0); status = 302; response = "{}"; assertFalse(extract().succeeded()); assertEquals(1, calls.get());
    }
    @Test void 当响应头后正文停顿时应该仍受响应超时约束() {
        bodyDelay = 700; properties.setResponseTimeoutMs(100); long start = System.nanoTime(); ProviderResult value = extract();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
        assertEquals(AiErrorCategory.TIMEOUT, value.errorCategory()); assertEquals(1, calls.get());
    }
    String completion(String content, String finish, String usage) throws Exception { var root = mapper.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("content", content);
        if (usage != null) root.set("usage", mapper.readTree(usage)); return root.toString(); }
}
