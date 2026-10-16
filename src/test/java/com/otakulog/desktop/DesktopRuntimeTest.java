package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DesktopRuntimeTest {

    private static final String TOKEN = "a1".repeat(32);
    private static final String VERSION = "1.1.0-beta.1";

    @Test
    void 当控制包合法时应该只接受对应凭据并只允许引导一次() {
        var runtime = runtime(packet(TOKEN, VERSION), new ByteArrayOutputStream());
        assertThat(runtime.matchesToken(TOKEN)).isTrue();
        assertThat(runtime.matchesToken(null)).isFalse();
        assertThat(runtime.matchesToken("b2".repeat(32))).isFalse();
        assertThat(runtime.claimBootstrap()).isTrue();
        assertThat(runtime.claimBootstrap()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "g123", "a1a1", "控制凭据"})
    void 当控制凭据不是六十四位十六进制时应该拒绝且不泄漏输入(String token) {
        assertThatThrownBy(() -> runtime(packet(token, VERSION), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("桌面控制初始化包无效");
    }

    @Test
    void 当初始化包过长损坏缺字段或版本不符时应该拒绝且不泄漏原文() {
        for (String input : new String[]{"x".repeat(4097) + "\n", "{bad\n", "{}\n",
                packet(TOKEN, "wrong"), packet(TOKEN, VERSION).stripTrailing(),
                packet("a".repeat(63), VERSION), packet("a".repeat(65), VERSION),
                packet("g".repeat(64), VERSION),
                "{\"controlToken\":\"" + TOKEN + "\",\"controlToken\":\"" + TOKEN + "\",\"version\":\"" + VERSION + "\"}\n",
                "{\"controlToken\":\"" + TOKEN + "\",\"version\":\"" + VERSION + "\"} {}\n"}) {
            assertThatThrownBy(() -> runtime(input, new ByteArrayOutputStream()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("桌面控制初始化包无效");
        }
    }

    @Test
    void 当应用真正就绪时应该只报告实际端口版本且父通道断开后关闭所属上下文() throws Exception {
        var input = new PipedInputStream();
        var parent = new PipedOutputStream(input);
        parent.write(packet(TOKEN, VERSION).getBytes(StandardCharsets.UTF_8));
        parent.flush();
        var output = new ByteArrayOutputStream();
        var runtime = new DesktopRuntime(input, new PrintStream(output, true, StandardCharsets.UTF_8),
                new ObjectMapper(), VERSION);
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
            runtime.ready(context, 31777);
            runtime.ready(context, 31777);
            String ready = output.toString(StandardCharsets.UTF_8);
            assertThat(ready.lines().toList()).hasSize(1);
            assertThat(ready).startsWith("OTAKULOG_DESKTOP_READY ").doesNotContain(TOKEN);
            var payload = new ObjectMapper().readTree(ready.substring(ready.indexOf(' ') + 1));
            assertThat(payload.path("port").asInt()).isEqualTo(31777);
            assertThat(payload.path("version").asText()).isEqualTo(VERSION);
            parent.close();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (context.isActive()) Thread.sleep(10);
            });
        } finally {
            parent.close();
            input.close();
        }
    }

    @Test
    void 当收到退出请求时应该异步正常关闭所属上下文() throws Exception {
        var input = new PipedInputStream();
        try (var parent = new PipedOutputStream(input); var context = new GenericApplicationContext()) {
            parent.write(packet(TOKEN, VERSION).getBytes(StandardCharsets.UTF_8));
            parent.flush();
            var runtime = new DesktopRuntime(input, new PrintStream(new ByteArrayOutputStream()),
                    new ObjectMapper(), VERSION);
            context.refresh();
            runtime.ready(context, 31778);
            runtime.shutdown();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (context.isActive()) Thread.sleep(10);
            });
        }
        input.close();
    }

    private DesktopRuntime runtime(String packet, ByteArrayOutputStream output) {
        return new DesktopRuntime(new ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output), new ObjectMapper(), VERSION);
    }

    static String packet(String token, String version) {
        return "{\"controlToken\":\"" + token + "\",\"version\":\"" + version + "\"}\n";
    }
}
