package com.otakulog.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.otakulog.OtakuLogApplication;
import com.otakulog.dto.BangumiImportDTO;
import com.otakulog.dto.RecommendationDTO;
import com.otakulog.repository.AnimeMemoryRepository;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import com.otakulog.repository.ExternalCollectionRepository;
import com.otakulog.repository.ImportBatchRepository;
import com.otakulog.repository.RecommendationFeedbackRepository;
import com.otakulog.repository.RecommendationShownRepository;
import com.otakulog.service.BangumiImportPreviewService;
import com.otakulog.service.RecommendationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopIdentityHttpTest {
    private static final String TOKEN = "e5".repeat(32);
    private static final String OLD_OWNER = "fictional-website-owner";
    private static final int SUBJECT = 983001;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @TempDir
    Path directory;

    @Test
    void 当受管桌面显示主页时应该标记本地入口并隐藏网站退出表单() throws Exception {
        try (var upstream = new FictionalUpstream(); var desktop = new ManagedDesktop(directory, upstream)) {
            assertThat(desktop.page).contains("<meta name=\"otakulog-desktop-managed\" content=\"true\">");
            assertThat(desktop.page).doesNotContain("action=\"/logout\"");
        }
    }

    @Test
    void 当桌面通过真实会话导入和反馈时应该使用本地身份并对重复请求只保存一次() throws Exception {
        try (var upstream = new FictionalUpstream(); var desktop = new ManagedDesktop(directory, upstream)) {
            var preview = desktop.data("POST", "/api/bangumi/import/preview", Map.of("username", "fictional-import"));
            var confirm = new BangumiImportDTO.ConfirmRequest(preview.path("previewKey").asText(), uuid(), true, true);
            int readCalls = upstream.calls.get();
            var imported = desktop.data("POST", "/api/bangumi/import/confirm", confirm);
            assertThat(imported.path("created").asInt()).isEqualTo(1);
            assertThat(desktop.data("POST", "/api/bangumi/import/confirm", confirm)).isEqualTo(imported);
            assertThat(upstream.calls.get()).isEqualTo(readCalls);
            assertThat(desktop.bean(ImportBatchRepository.class).findAll()).singleElement()
                    .satisfies(batch -> assertThat(batch.getOwner()).isEqualTo("desktop-local"));
            assertThat(desktop.bean(ExternalCollectionRepository.class).count()).isEqualTo(1);
            assertThat(desktop.bean(AnimeRepository.class).findAll()).singleElement().satisfies(anime -> {
                assertThat(anime.getRemark()).isEmpty();
                assertThat(anime.getEndDate()).isNull();
            });
            assertThat(desktop.bean(AnimeMemoryRepository.class).count()).isZero();
            assertThat(desktop.bean(EpisodeRecordRepository.class).count()).isEqualTo(3);
            assertThat(desktop.bean(EpisodeRecordRepository.class).findAll()).allSatisfy(record ->
                    assertThat(record.getWatchedDate()).isNull());

            var result = desktop.data("GET", "/api/recommendations?mode=TAGS&tags=%E5%89%A7%E6%83%85", null);
            assertThat(result.path("entries")).hasSize(1);
            assertThat(result.path("entries").get(0).path("id").asInt()).isEqualTo(SUBJECT);
            int recommendationCalls = upstream.calls.get();
            String batch = result.path("batchKey").asText();
            var shown = new RecommendationDTO.ShownRequest(batch, List.of(SUBJECT));
            desktop.data("POST", "/api/recommendations/shown", shown);
            desktop.data("POST", "/api/recommendations/shown", shown);
            assertThat(desktop.bean(RecommendationShownRepository.class).count()).isEqualTo(1);
            var feedback = new RecommendationDTO.FeedbackRequest(uuid(), batch, SUBJECT, RecommendationDTO.Kind.WATCHED);
            var saved = desktop.data("POST", "/api/recommendations/feedback", feedback);
            assertThat(saved.path("owner").asText()).isEqualTo("desktop-local");
            assertThat(desktop.data("POST", "/api/recommendations/feedback", feedback)).isEqualTo(saved);
            assertThat(desktop.bean(RecommendationFeedbackRepository.class).count()).isEqualTo(1);
            assertThat(desktop.bean(AnimeRepository.class).count()).isEqualTo(1);
            assertThat(desktop.bean(EpisodeRecordRepository.class).count()).isEqualTo(3);
            assertThat(desktop.bean(EpisodeRecordRepository.class).findAll()).allSatisfy(record ->
                    assertThat(record.getWatchedDate()).isNull());
            assertThat(upstream.calls.get()).isEqualTo(recommendationCalls);
        }
    }

    @Test
    void 当收藏预览或推荐批次属于其他身份时应该经HTTP拒绝且没有部分写入() throws Exception {
        try (var upstream = new FictionalUpstream(); var desktop = new ManagedDesktop(directory, upstream)) {
            var preview = desktop.bean(BangumiImportPreviewService.class).preview(OLD_OWNER, "fictional-foreign");
            var batch = desktop.bean(RecommendationService.class).recommend(OLD_OWNER, false,
                    RecommendationDTO.Mode.TAGS, null, List.of("剧情"), List.of());
            assertThat(batch.entries()).hasSize(1);
            JsonNode before = desktop.snapshot();
            int calls = upstream.calls.get();
            assertThat(desktop.send("POST", "/api/bangumi/import/confirm",
                    new BangumiImportDTO.ConfirmRequest(preview.previewKey(), uuid(), true, true), true, true).statusCode()).isEqualTo(404);
            assertThat(desktop.send("POST", "/api/recommendations/shown",
                    new RecommendationDTO.ShownRequest(batch.batchKey(), List.of(SUBJECT)), true, true).statusCode()).isEqualTo(404);
            assertThat(desktop.send("POST", "/api/recommendations/feedback",
                    new RecommendationDTO.FeedbackRequest(uuid(), batch.batchKey(), SUBJECT, RecommendationDTO.Kind.WATCHED), true, true)
                    .statusCode()).isEqualTo(404);
            assertThat(desktop.snapshot()).isEqualTo(before);
            assertThat(desktop.bean(RecommendationShownRepository.class).count()).isZero();
            assertThat(upstream.calls.get()).isEqualTo(calls);
        }
    }

    @Test
    void 当业务请求没有会话或页面令牌时应该拒绝并保持资料不变() throws Exception {
        try (var upstream = new FictionalUpstream(); var desktop = new ManagedDesktop(directory, upstream)) {
            var preview = desktop.data("POST", "/api/bangumi/import/preview", Map.of("username", "fictional-protected"));
            var batch = desktop.data("GET", "/api/recommendations?mode=TAGS&tags=%E5%89%A7%E6%83%85", null);
            var writes = Map.of(
                    "/api/bangumi/import/preview", Map.of("username", "fictional-blocked"),
                    "/api/bangumi/import/confirm", new BangumiImportDTO.ConfirmRequest(preview.path("previewKey").asText(), uuid(), true, true),
                    "/api/recommendations/shown", new RecommendationDTO.ShownRequest(batch.path("batchKey").asText(), List.of(SUBJECT)),
                    "/api/recommendations/feedback", new RecommendationDTO.FeedbackRequest(uuid(), batch.path("batchKey").asText(), SUBJECT, RecommendationDTO.Kind.WATCHED));
            JsonNode before = desktop.snapshot();
            int calls = upstream.calls.get();
            for (var write : writes.entrySet()) {
                assertThat(desktop.send("POST", write.getKey(), write.getValue(), false, false).statusCode()).isEqualTo(401);
                assertThat(desktop.send("POST", write.getKey(), write.getValue(), false, true).statusCode()).isEqualTo(401);
                assertThat(desktop.send("POST", write.getKey(), write.getValue(), true, false).statusCode()).isEqualTo(403);
            }
            assertThat(desktop.send("GET", "/api/recommendations", null, false, false).statusCode()).isEqualTo(401);
            assertThat(desktop.snapshot()).isEqualTo(before);
            assertThat(desktop.bean(RecommendationShownRepository.class).count()).isZero();
            assertThat(upstream.calls.get()).isEqualTo(calls);
        }
    }

    @Test
    void 当恢复网站历史后应该保留原归属且本地身份不能重发旧请求键() throws Exception {
        try (var upstream = new FictionalUpstream()) {
            BangumiImportDTO.ConfirmRequest oldImport;
            RecommendationDTO.FeedbackRequest oldFeedback;
            String backup;
            try (var source = new ManagedDesktop(directory.resolve("虚构旧资料"), upstream)) {
                var importer = source.bean(BangumiImportPreviewService.class);
                var preview = importer.preview(OLD_OWNER, "fictional-history");
                oldImport = new BangumiImportDTO.ConfirmRequest(preview.previewKey(), uuid(), true, true);
                importer.confirm(OLD_OWNER, oldImport);
                var recommender = source.bean(RecommendationService.class);
                var batch = recommender.recommend(OLD_OWNER, false, RecommendationDTO.Mode.TAGS, null, List.of("剧情"), List.of());
                oldFeedback = new RecommendationDTO.FeedbackRequest(uuid(), batch.batchKey(), SUBJECT, RecommendationDTO.Kind.NOT_INTERESTED);
                recommender.feedback(OLD_OWNER, oldFeedback);
                backup = source.send("GET", "/api/anime/export", null, true, false).body();
            }
            int calls = upstream.calls.get();
            try (var target = new ManagedDesktop(directory.resolve("虚构恢复资料"), upstream)) {
                assertThat(target.data("POST", "/api/anime/import/preview", JSON.readTree(backup)).path("valid").asBoolean()).isTrue();
                target.data("POST", "/api/anime/import", JSON.readTree(backup));
                JsonNode restored = target.snapshot();
                JsonNode original = JSON.readTree(backup);
                assertThat(restored.path("importBatches")).isEqualTo(original.path("importBatches"));
                assertThat(restored.path("recommendationFeedback")).isEqualTo(original.path("recommendationFeedback"));
                assertThat(restored.path("importBatches").get(0).path("owner").asText()).isEqualTo(OLD_OWNER);
                assertThat(restored.path("recommendationFeedback").get(0).path("owner").asText()).isEqualTo(OLD_OWNER);
                assertThat(target.send("POST", "/api/bangumi/import/confirm", oldImport, true, true).statusCode()).isEqualTo(409);
                assertThat(target.send("POST", "/api/recommendations/feedback", oldFeedback, true, true).statusCode()).isEqualTo(409);
                assertThat(target.snapshot()).isEqualTo(restored);
                assertThat(target.bean(RecommendationShownRepository.class).count()).isZero();
                assertThat(upstream.calls.get()).isEqualTo(calls);
            }
        }
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    private static String extract(String html, String regex) {
        var matcher = Pattern.compile(regex).matcher(html);
        assertThat(matcher.find()).as("页面应提供会话令牌").isTrue();
        return matcher.group(1);
    }

    private class ManagedDesktop implements AutoCloseable {
        final ConfigurableApplicationContext application;
        final PipedInputStream input = new PipedInputStream();
        final PipedOutputStream parent = new PipedOutputStream(input);
        final String base, cookie, page, csrf, csrfHeader;

        ManagedDesktop(Path dataDirectory, FictionalUpstream upstream) throws Exception {
            ConfigurableApplicationContext started = null;
            try {
                parent.write(DesktopRuntimeTest.packet(TOKEN, "test-identity-version").getBytes(StandardCharsets.UTF_8));
                parent.flush();
                var runtime = new DesktopRuntime(input, new PrintStream(new ByteArrayOutputStream()), JSON, "test-identity-version");
                var launcher = new SpringApplication(OtakuLogApplication.class);
                launcher.addInitializers(context -> context.getBeanFactory().registerSingleton("desktopRuntime", runtime));
                launcher.addListeners(runtime);
                started = launcher.run("--spring.profiles.active=desktop", "--app.desktop.managed=true",
                        "--app.desktop.data-dir=" + dataDirectory, "--spring.main.banner-mode=off",
                        "--otakulog.bangumi.base-url=" + upstream.base());
                application = started;
                base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) application).getWebServer().getPort();
                var bootstrap = client.send(HttpRequest.newBuilder(URI.create(base + "/internal/desktop/bootstrap"))
                        .header("X-OtakuLog-Desktop-Token", TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(bootstrap.statusCode()).isEqualTo(200);
                cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
                var home = send("GET", "/", null, true, false);
                assertThat(home.statusCode()).isEqualTo(200);
                page = home.body();
                csrf = extract(page, "<meta name=\"_csrf\" content=\"([^\"]+)\"");
                csrfHeader = extract(page, "<meta name=\"_csrf_header\" content=\"([^\"]+)\"");
            } catch (Exception | AssertionError failure) {
                if (started != null) started.close();
                parent.close();
                input.close();
                throw failure;
            }
        }

        <T> T bean(Class<T> type) { return application.getBean(type); }

        HttpResponse<String> send(String method, String path, Object body, boolean session, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("Origin", base);
            if (session) request.header("Cookie", cookie);
            if (token) request.header(csrfHeader, csrf);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode data(String method, String path, Object body) throws Exception {
            var response = send(method, path, body, true, true);
            assertThat(response.statusCode()).as("接口 %s：%s", path, response.body()).isEqualTo(200);
            return JSON.readTree(response.body()).path("data");
        }

        JsonNode snapshot() throws Exception {
            var response = send("GET", "/api/anime/export", null, true, false);
            assertThat(response.statusCode()).isEqualTo(200);
            ObjectNode backup = (ObjectNode) JSON.readTree(response.body());
            backup.remove("exportedAt");
            return backup;
        }

        @Override
        public void close() throws Exception {
            application.close();
            parent.close();
            input.close();
        }
    }

    private static class FictionalUpstream implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server;

        FictionalUpstream() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                Object response;
                if (path.contains("/collections")) {
                    response = Map.of("total", 1, "data", List.of(Map.of("subject", Map.of("id", 983002, "name", "虚构身份收藏", "eps", 3),
                            "type", 2, "ep_status", 0, "rate", 8, "tags", List.of("剧情"), "comment", "虚构原始评论，不能自动变成观影记忆")));
                } else if (path.equals("/v0/search/subjects")) {
                    response = Map.of("data", List.of(Map.of("id", SUBJECT, "name", "虚构身份推荐")));
                } else {
                    response = Map.of("id", SUBJECT, "type", 2, "name", "虚构身份推荐", "date", "2020-01-01",
                            "tags", List.of(Map.of("name", "剧情", "count", 1)));
                }
                byte[] bytes = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override
        public void close() { server.stop(0); }
    }
}
