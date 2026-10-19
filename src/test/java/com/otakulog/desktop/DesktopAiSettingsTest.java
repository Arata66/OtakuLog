package com.otakulog.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import com.otakulog.config.TasteAiProperties;
import com.otakulog.config.TasteAiRuntime;
import com.otakulog.entity.Anime;
import com.otakulog.repository.AnimeRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class DesktopAiSettingsTest {
    private static final String TOKEN = "a8".repeat(32);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicInteger calls = new AtomicInteger();
    @TempDir static Path directory;
    private ConfigurableApplicationContext application;
    private PipedInputStream input;
    private PipedOutputStream parent;
    private HttpServer supplier;
    private String base, cookie, csrf, csrfHeader, aiPath;
    private volatile String authorization;
    private volatile Runnable onRequest;

    @BeforeAll
    void 启动隔离桌面与虚构模型() throws Exception {
        supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        supplier.createContext("/ai", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            if (onRequest != null) onRequest.run();
            byte[] response = JSON.writeValueAsBytes(Map.of("choices", java.util.List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", "{\"insights\":[]}")))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        supplier.start();
        input = new PipedInputStream();
        parent = new PipedOutputStream(input);
        parent.write(DesktopRuntimeTest.packet(TOKEN, "test-ai-settings-version").getBytes(StandardCharsets.UTF_8));
        parent.flush();
        var runtime = new DesktopRuntime(input, new PrintStream(new ByteArrayOutputStream()), JSON, "test-ai-settings-version");
        var launcher = new SpringApplication(OtakuLogApplication.class);
        launcher.addInitializers(context -> context.getBeanFactory().registerSingleton("desktopRuntime", runtime));
        launcher.addListeners(runtime);
        application = launcher.run("--spring.profiles.active=desktop", "--app.desktop.managed=true",
                "--app.desktop.data-dir=" + directory, "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--app.ai.endpoint=http://127.0.0.1:" + supplier.getAddress().getPort() + "/ai");
        base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) application).getWebServer().getPort();
        var bootstrap = control("/bootstrap", "{}", TOKEN);
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String page = business("GET", "/", null).body();
        csrf = extract(page, "<meta name=\"_csrf\" content=\"([^\"]+)\"");
        csrfHeader = extract(page, "<meta name=\"_csrf_header\" content=\"([^\"]+)\"");
        assertThat(usage().path("configured").asBoolean()).isFalse();
        assertThat(application.getBean(TasteAiRuntime.class).snapshot().apiKey()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @BeforeEach
    void 清理本次测试的虚构账本() throws Exception {
        onRequest = null;
        calls.set(0);
        authorization = null;
        assertThat(control("/ai-settings-cancel", "{}", TOKEN).statusCode()).isEqualTo(200);
        apply(false, "", 20);
        var jdbc = application.getBean(JdbcTemplate.class);
        jdbc.update("DELETE FROM ai_call");
        jdbc.update("UPDATE ai_quota SET quota_date=?,reserved_calls=0 WHERE id=1", LocalDate.now());
        var anime = new Anime();
        anime.setName("虚构配置验收作品" + UUID.randomUUID());
        long animeId = application.getBean(AnimeRepository.class).saveAndFlush(anime).getId();
        var memory = business("POST", "/api/anime/" + animeId + "/memories", JSON.writeValueAsString(
                Map.of("key", UUID.randomUUID().toString(), "content", "配乐动人，节奏太慢")));
        assertThat(memory.statusCode()).isEqualTo(200);
        long memoryId = data(memory).path("id").asLong();
        aiPath = "/api/anime/" + animeId + "/memories/" + memoryId + "/insights/ai";
    }

    @AfterAll
    void 关闭本次拥有的隔离资源() throws Exception {
        if (application != null) application.close();
        if (parent != null) parent.close();
        if (input != null) input.close();
        if (supplier != null) supplier.stop(0);
    }

    @Test
    void 当设置启用替换禁用或清除时应该立即生效且不调用模型() throws Exception {
        assertThat(usage().path("configured").asBoolean()).isFalse();
        assertThat(data(business("POST", aiPath + "/preview", request())).path("configured").asBoolean()).isFalse();
        assertThat(business("POST", aiPath + "/extract", request()).statusCode()).isEqualTo(400);
        assertThat(apply(true, "  fictional-first-key  ", 7).path("available").asBoolean()).isTrue();
        assertThat(application.getBean(TasteAiRuntime.class).snapshot().toString()).doesNotContain("fictional-first-key");
        assertThat(application.getBean(TasteAiProperties.class).getApiKey()).isEmpty();
        assertThat(usage().path("dailyLimit").asInt()).isEqualTo(7);
        assertThat(data(business("POST", aiPath + "/preview", request())).path("configured").asBoolean()).isTrue();
        apply(true, "fictional-second-key", 8);
        assertThat(usage().path("dailyLimit").asInt()).isEqualTo(8);
        apply(false, "fictional-second-key", 9);
        assertThat(usage().path("configured").asBoolean()).isFalse();
        assertThat(apply(false, "", 0).path("available").asBoolean()).isFalse();
        assertThat(usage().path("dailyLimit").asInt()).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    void 当控制请求非法或没有主进程凭据时应该拒绝且不回显密钥(CapturedOutput output) throws Exception {
        String secret = "fictional-secret-do-not-log";
        String valid = JSON.writeValueAsString(Map.of("enabled", true, "apiKey", secret, "dailyLimit", 5));
        assertThat(control("/ai-settings", valid, null).statusCode()).isEqualTo(403);
        assertThat(control("/ai-settings", valid, "wrong").statusCode()).isEqualTo(403);
        assertThat(business("POST", "/internal/desktop/ai-settings", valid).statusCode()).isEqualTo(403);
        for (String invalid : java.util.List.of("{}", "[]", "null", valid + " {}", valid.replace("true", "\"true\""),
                valid.replace("5", "5.0"), valid.replace("5", "\"5\""), valid.replace("5", "-1"), valid.replace("5", "1001"),
                valid.replace("\"apiKey\":\"" + secret + "\"", "\"apiKey\":null"),
                "{\"enabled\":true,\"apiKey\":\"\",\"dailyLimit\":5}",
                "{\"enabled\":false,\"apiKey\":\"bad\\u0000key\",\"dailyLimit\":5}",
                "{\"enabled\":false,\"apiKey\":\"" + "k".repeat(513) + "\",\"dailyLimit\":5}",
                valid.replace("}", ",\"endpoint\":\"http://127.0.0.1/\"}"),
                valid.replace("}", ",\"apiKey\":\"duplicate\"}"), " ".repeat(4097) + valid)) {
            var rejected = control("/ai-settings", invalid, TOKEN);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).doesNotContain(secret, "duplicate", "127.0.0.1/");
        }
        for (String path : java.util.List.of("/ai-settings-lock", "/ai-settings-cancel")) {
            assertThat(control(path, "{\"apiKey\":\"" + secret + "\"}", TOKEN).statusCode()).isEqualTo(400);
            assertThat(control(path, "{} {}", TOKEN).statusCode()).isEqualTo(400);
        }
        assertThat(usage().path("configured").asBoolean()).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void 当模型请求运行时应该立即拒绝配置并在结束后允许且同键不重发() throws Exception {
        apply(true, "  fictional-first-key  ", 2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        onRequest = () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("虚构模型等待超时"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        };
        String request = request();
        var pending = CompletableFuture.supplyAsync(() -> {
            try { return business("POST", aiPath + "/extract", request); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(authorization).isEqualTo("Bearer fictional-first-key");
            var started = System.nanoTime();
            assertThat(control("/ai-settings-lock", "{}", TOKEN).statusCode()).isEqualTo(409);
            assertThat(control("/ai-settings", "{\"enabled\":false,\"apiKey\":\"\",\"dailyLimit\":0}", TOKEN).statusCode()).isEqualTo(409);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(data(business("POST", aiPath + "/extract", request)).path("status").asText()).isEqualTo("RUNNING");
        } finally { release.countDown(); }
        assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        apply(true, "fictional-replaced-key", 2);
        assertThat(business("POST", aiPath + "/extract", request).statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(1);
        assertThat(usage().path("callsToday").asInt()).isEqualTo(1);
        assertThat(usage().path("remainingCalls").asInt()).isEqualTo(1);
        apply(false, "", 1);
        apply(true, "fictional-last-key", 1);
        assertThat(usage().path("callsToday").asInt()).isEqualTo(1);
        String changed = JSON.writeValueAsString(Map.of("sourceVersion", 0, "requestKey", UUID.randomUUID().toString(),
                "selections", Map.of("content", "配乐")));
        assertThat(business("POST", aiPath + "/extract", changed).statusCode()).isEqualTo(409);
        assertThat(calls).hasValue(1);
    }

    @Test
    void 当主进程持有配置租约时应该阻止新调用直到提交或取消() throws Exception {
        apply(true, "fictional-before-key", 2);
        assertThat(control("/ai-settings-lock", "{}", TOKEN).statusCode()).isEqualTo(200);
        assertThat(control("/ai-settings-lock", "{}", TOKEN).statusCode()).isEqualTo(409);
        assertThat(business("POST", aiPath + "/extract", request()).statusCode()).isEqualTo(409);
        assertThat(control("/ai-settings", "{}", TOKEN).statusCode()).isEqualTo(400);
        assertThat(business("POST", aiPath + "/extract", request()).statusCode()).isEqualTo(409);
        assertThat(control("/ai-settings-cancel", "{}", TOKEN).statusCode()).isEqualTo(200);
        assertThat(usage().path("configured").asBoolean()).isTrue();
        assertThat(control("/ai-settings-lock", "{}", TOKEN).statusCode()).isEqualTo(200);
        apply(true, "fictional-after-key", 2);
        assertThat(business("POST", aiPath + "/extract", request()).statusCode()).isEqualTo(200);
        assertThat(authorization).isEqualTo("Bearer fictional-after-key");
        assertThat(calls).hasValue(1);
    }

    private JsonNode apply(boolean enabled, String key, int limit) throws Exception {
        var response = control("/ai-settings", JSON.writeValueAsString(Map.of("enabled", enabled, "apiKey", key, "dailyLimit", limit)), TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("apiKey", "fictional-");
        return data(response);
    }

    private JsonNode usage() throws Exception { return data(business("GET", "/api/taste/ai/usage", null)); }
    private String request() throws Exception { return JSON.writeValueAsString(Map.of("sourceVersion", 0, "requestKey", UUID.randomUUID().toString())); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()).path("data"); }

    private HttpResponse<String> control(String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/internal/desktop" + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) request.header("X-OtakuLog-Desktop-Token", token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> business(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("Cookie", cookie);
        if (csrfHeader != null) request.header(csrfHeader, csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String extract(String page, String regex) {
        var matcher = Pattern.compile(regex).matcher(page);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
