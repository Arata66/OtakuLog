package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DesktopSecurityTest {

    private static final String TOKEN = "c3".repeat(32);
    @TempDir
    static Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private ConfigurableApplicationContext application;
    private PipedInputStream input;
    private PipedOutputStream parent;
    private int port;
    private String managedCookie;
    private final ByteArrayOutputStream readyOutput = new ByteArrayOutputStream();

    @BeforeAll
    void 启动本次隔离桌面库() throws Exception {
        input = new PipedInputStream();
        parent = new PipedOutputStream(input);
        parent.write(DesktopRuntimeTest.packet(TOKEN, "test-desktop-version").getBytes(StandardCharsets.UTF_8));
        parent.flush();
        var runtime = new DesktopRuntime(input, new PrintStream(readyOutput),
                new ObjectMapper(), "test-desktop-version");
        var launcher = new SpringApplication(OtakuLogApplication.class, TestIdentityController.class);
        launcher.addInitializers(context -> context.getBeanFactory().registerSingleton("desktopRuntime", runtime));
        launcher.addListeners(runtime);
        application = launcher.run("--spring.profiles.active=desktop", "--app.desktop.managed=true",
                "--app.desktop.data-dir=" + directory, "--spring.main.banner-mode=off");
        port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    @AfterAll
    void 关闭本次拥有的资源() throws Exception {
        if (application != null) application.close();
        if (parent != null) parent.close();
        if (input != null) input.close();
    }

    @Test
    @Order(1)
    void 当主进程完成引导时应该获得一次会话且业务仍要求认证和CSRF() throws Exception {
        String ready = readyOutput.toString(StandardCharsets.UTF_8);
        assertThat(ready.lines().toList()).hasSize(1);
        var payload = new ObjectMapper().readTree(ready.substring(ready.indexOf(' ') + 1));
        assertThat(payload.path("port").asInt()).isEqualTo(port);
        assertThat(payload.path("version").asText()).isEqualTo("test-desktop-version");
        assertThat(request("GET", "/api/groups", null, null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/groups", TOKEN, null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/groups", null, null, null, "{}").statusCode()).isEqualTo(401);
        assertThat(request("POST", "/internal/desktop/bootstrap", null, null, null, "{}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/internal/desktop/bootstrap", "d4".repeat(32), null, null, "{}").statusCode()).isEqualTo(403);

        var bootstrap = request("POST", "/internal/desktop/bootstrap", TOKEN, null, null, "{}");
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        assertThat(bootstrap.body()).contains("desktop-local");
        String cookieHeader = bootstrap.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookieHeader).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/");
        String cookie = cookieHeader.split(";", 2)[0];
        managedCookie = cookie;
        assertThat(request("GET", "/desktop-test/identity", null, cookie, null, null).body()).isEqualTo("desktop-local:ROLE_USER");
        assertThat(request("POST", "/internal/desktop/bootstrap", TOKEN, null, null, "{}").statusCode()).isEqualTo(409);
        assertThat(request("POST", "/internal/desktop/shutdown", null, cookie, null, "{}").statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/groups", null, cookie, null, null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/groups", null, cookie, null, "{\"name\":\"虚构桌面分组\"}").statusCode()).isEqualTo(403);

        var page = request("GET", "/", null, cookie, null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        String csrf = extract(page.body(), "<meta name=\"_csrf\" content=\"([^\"]+)\"");
        String csrfHeader = extract(page.body(), "<meta name=\"_csrf_header\" content=\"([^\"]+)\"");
        var save = HttpRequest.newBuilder(URI.create(base() + "/api/groups"))
                .header("Cookie", cookie).header("Origin", base()).header(csrfHeader, csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"虚构桌面分组\"}")).build();
        assertThat(client.send(save, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/groups", null, cookie, null, null).body()).contains("虚构桌面分组");
        assertThat(request("GET", "/api/groups", null, cookie, "http://localhost:5173", null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/groups", null, cookie, "null", null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/internal/desktop/shutdown", TOKEN, null, "https://example.invalid", "{}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/login", null, null, null, "username=test&password=test").statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void 当受管会话闲置超过网站默认时限时应该继续访问业务() throws Exception {
        simulateIdle(application, managedCookie, Duration.ofMinutes(31));
        var response = request("GET", "/api/groups", null, managedCookie, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("虚构桌面分组");
    }

    @Test
    @Order(2)
    void 当仅启用桌面资料库而没有受管通道时应该保留网站登录() throws Exception {
        var launcher = new SpringApplication(OtakuLogApplication.class);
        try (var unmanaged = launcher.run("--spring.profiles.active=desktop", "--app.desktop.managed=false",
                "--app.desktop.data-dir=" + directory.resolve("未受管虚构库"), "--spring.main.banner-mode=off")) {
            int unmanagedPort = ((ServletWebServerApplicationContext) unmanaged).getWebServer().getPort();
            String unmanagedBase = "http://127.0.0.1:" + unmanagedPort;
            assertThat(unmanaged.getBeansOfType(DesktopRuntime.class)).isEmpty();
            assertThat(client.send(HttpRequest.newBuilder(URI.create(unmanagedBase + "/api/groups")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var page = client.send(HttpRequest.newBuilder(URI.create(unmanagedBase + "/login")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            String csrf = extract(page.body(), "name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
            String cookie = page.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            var login = client.send(HttpRequest.newBuilder(URI.create(unmanagedBase + "/login"))
                    .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=test&password=test&_csrf="
                            + java.net.URLEncoder.encode(csrf, StandardCharsets.UTF_8))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(302);
            String session = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            assertThat(client.send(HttpRequest.newBuilder(URI.create(unmanagedBase + "/api/groups"))
                            .header("Cookie", session).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(200);
            simulateIdle(unmanaged, session, Duration.ofMinutes(31));
            assertThat(client.send(HttpRequest.newBuilder(URI.create(unmanagedBase + "/api/groups"))
                            .header("Cookie", session).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(401);
        }
    }

    @Test
    @Order(2)
    void 当Host不是实际回环端口时应该拒绝包括携带正确控制凭据的请求() throws Exception {
        for (String host : new String[]{"localhost:" + port, "example.invalid:" + port, "127.0.0.1:1"}) {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("POST /internal/desktop/bootstrap HTTP/1.1\r\nHost: " + host
                        + "\r\nX-OtakuLog-Desktop-Token: " + TOKEN + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response).startsWith("HTTP/1.1 403");
            }
        }
    }

    @Test
    @Order(3)
    void 当主进程发起合法退出时应该先返回成功再正常释放所属服务() throws Exception {
        assertThat(request("POST", "/internal/desktop/shutdown", TOKEN, null, base(), "{}").statusCode()).isEqualTo(200);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (application.isActive()) Thread.sleep(10);
        });
    }

    private HttpResponse<String> request(String method, String path, String token, String cookie, String origin, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5));
        if (token != null) builder.header("X-OtakuLog-Desktop-Token", token);
        if (cookie != null) builder.header("Cookie", cookie);
        if (origin != null) builder.header("Origin", origin);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String extract(String html, String regex) {
        var matcher = Pattern.compile(regex).matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void simulateIdle(ConfigurableApplicationContext context, String cookie, Duration idle) throws Exception {
        var server = (TomcatWebServer) ((ServletWebServerApplicationContext) context).getWebServer();
        var servletContext = (org.apache.catalina.Context) server.getTomcat().getHost().findChildren()[0];
        var session = servletContext.getManager().findSession(cookie.substring(cookie.indexOf('=') + 1));
        assertThat(session).isNotNull();
        // 只推进本次测试会话的闲置时间，避免让回归真实等待三十一分钟。
        session.setCreationTime(System.currentTimeMillis() - idle.toMillis());
    }

    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class TestIdentityController {

        @GetMapping("/desktop-test/identity")
        String identity(Principal principal) {
            var authentication = (org.springframework.security.core.Authentication) principal;
            return principal.getName() + ":" + authentication.getAuthorities().iterator().next().getAuthority();
        }
    }
}
