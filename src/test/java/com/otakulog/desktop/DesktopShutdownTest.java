package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import com.otakulog.entity.AnimeGroup;
import com.otakulog.repository.AnimeGroupRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopShutdownTest {

    private static final String TOKEN = "e5".repeat(32);

    @TempDir
    Path directory;

    @Test
    void 当退出遇到超过十五秒的在途写入时应该继续等待提交并返回响应后再关库() throws Exception {
        var gate = new WriteGate();
        var closing = new CountDownLatch(1);
        var input = new PipedInputStream();
        var parent = new PipedOutputStream(input);
        ConfigurableApplicationContext application = null;
        try {
            parent.write(DesktopRuntimeTest.packet(TOKEN, "test-desktop-version").getBytes(StandardCharsets.UTF_8));
            parent.flush();
            var runtime = new DesktopRuntime(input, new PrintStream(new ByteArrayOutputStream()),
                    new ObjectMapper(), "test-desktop-version");
            var launcher = new SpringApplication(OtakuLogApplication.class, HeldWriteController.class);
            launcher.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("desktopRuntime", runtime);
                context.getBeanFactory().registerSingleton("desktopShutdownWriteGate", gate);
            });
            launcher.addListeners(runtime);
            application = launcher.run("--spring.profiles.active=desktop", "--app.desktop.managed=true",
                    "--app.desktop.data-dir=" + directory, "--spring.main.banner-mode=off");
            application.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) closing.countDown();
            });
            int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            var dataSource = application.getBean(HikariDataSource.class);
            String jdbcUrl = dataSource.getJdbcUrl();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var bootstrap = client.send(control(origin, "/bootstrap"), HttpResponse.BodyHandlers.ofString());
            assertThat(bootstrap.statusCode()).isEqualTo(200);
            String cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            var page = client.send(HttpRequest.newBuilder(URI.create(origin + "/")).header("Cookie", cookie)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            String csrf = extract(page.body(), "<meta name=\"_csrf\" content=\"([^\"]+)\"");
            String csrfHeader = extract(page.body(), "<meta name=\"_csrf_header\" content=\"([^\"]+)\"");
            var write = HttpRequest.newBuilder(URI.create(origin + "/desktop-test/held-write"))
                    .header("Cookie", cookie).header("Origin", origin).header(csrfHeader, csrf)
                    .timeout(Duration.ofSeconds(40)).POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.sendAsync(write, HttpResponse.BodyHandlers.ofString());
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();

            var shutdown = client.send(control(origin, "/shutdown"), HttpResponse.BodyHandlers.ofString());
            assertThat(shutdown.statusCode()).isEqualTo(200);
            assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
            // close 持有上下文关闭锁，第二次 close 返回才代表资源销毁流程结束。
            var completed = CompletableFuture.runAsync(application::close);
            assertThatThrownBy(() -> completed.get(16, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(dataSource.isClosed()).isFalse();
            assertThat(response.isDone()).isFalse();

            gate.release.countDown();
            var saved = response.get(10, TimeUnit.SECONDS);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).isEqualTo("虚构退出等待写入完成");
            completed.get(10, TimeUnit.SECONDS);
            assertThat(dataSource.isClosed()).isTrue();
            try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM anime_group WHERE name = '虚构退出等待写入'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        } finally {
            gate.release.countDown();
            if (application != null) application.close();
            parent.close();
            input.close();
        }
    }

    private HttpRequest control(String origin, String path) {
        return HttpRequest.newBuilder(URI.create(origin + "/internal/desktop" + path))
                .header("X-OtakuLog-Desktop-Token", TOKEN).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private String extract(String html, String expression) {
        var matcher = Pattern.compile(expression).matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    static final class WriteGate {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    @RestController
    @TestComponent
    static class HeldWriteController {

        private final AnimeGroupRepository repository;
        private final WriteGate gate;

        HeldWriteController(AnimeGroupRepository repository, WriteGate gate) {
            this.repository = repository;
            this.gate = gate;
        }

        @PostMapping("/desktop-test/held-write")
        @Transactional
        public String write() throws InterruptedException {
            var group = new AnimeGroup();
            group.setName("虚构退出等待写入");
            repository.saveAndFlush(group);
            gate.entered.countDown();
            if (!gate.release.await(40, TimeUnit.SECONDS)) throw new IllegalStateException("测试写入没有被释放");
            return "虚构退出等待写入完成";
        }
    }
}
