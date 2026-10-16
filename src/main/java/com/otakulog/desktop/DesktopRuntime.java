package com.otakulog.desktop;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopRuntime implements ApplicationListener<ApplicationReadyEvent> {

    private static final int MAX_INITIALIZATION_BYTES = 4096;
    private final InputStream input;
    private final PrintStream output;
    private final ObjectMapper mapper;
    private final String applicationVersion;
    private final byte[] controlToken;
    private final AtomicBoolean bootstrapped = new AtomicBoolean();
    private final AtomicBoolean announced = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile ConfigurableApplicationContext context;

    public DesktopRuntime(InputStream input, PrintStream output, ObjectMapper mapper, String applicationVersion) {
        this.input = input;
        this.output = output;
        this.mapper = mapper;
        this.applicationVersion = applicationVersion;
        this.controlToken = readInitialization();
    }

    private byte[] readInitialization() {
        try {
            var bytes = new ByteArrayOutputStream();
            for (int next; (next = input.read()) != '\n'; ) {
                if (next == -1 || bytes.size() >= MAX_INITIALIZATION_BYTES) throw invalidInitialization();
                bytes.write(next);
            }
            var strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try (var parser = strictMapper.createParser(bytes.toByteArray())) {
                JsonNode packet = strictMapper.readTree(parser);
                if (packet == null || !packet.isObject() || packet.size() != 2 || parser.nextToken() != null
                        || !packet.path("controlToken").isTextual() || !packet.path("version").isTextual()
                        || !packet.path("controlToken").asText().matches("[0-9a-fA-F]{64}")
                        || applicationVersion == null || applicationVersion.isBlank()
                        || !applicationVersion.equals(packet.path("version").asText())) {
                    throw invalidInitialization();
                }
                return packet.path("controlToken").asText().getBytes(StandardCharsets.US_ASCII);
            }
        } catch (IOException | RuntimeException failure) {
            // JSON 异常可能携带原始输入，不能把它作为异常原因交给启动日志。
            throw invalidInitialization();
        }
    }

    private IllegalArgumentException invalidInitialization() {
        return new IllegalArgumentException("桌面控制初始化包无效");
    }

    public boolean matchesToken(String token) {
        return token != null && MessageDigest.isEqual(controlToken, token.getBytes(StandardCharsets.UTF_8));
    }

    public boolean claimBootstrap() {
        return bootstrapped.compareAndSet(false, true);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof ServletWebServerApplicationContext application) {
            ready(application, application.getWebServer().getPort());
        }
    }

    void ready(ConfigurableApplicationContext application, int port) {
        if (!announced.compareAndSet(false, true)) return;
        context = application;
        try {
            output.println("OTAKULOG_DESKTOP_READY " + mapper.writeValueAsString(
                    Map.of("port", port, "version", applicationVersion)));
            output.flush();
        } catch (IOException failure) {
            shutdown();
            throw new IllegalStateException("无法报告桌面启动状态");
        }
        Thread monitor = new Thread(() -> {
            try {
                // 初始化后不再接受命令；继续读取仅用于发现父进程控制管道断开。
                while (input.read() != -1) { }
            } catch (IOException ignored) {
                // 管道错误与正常 EOF 都表示父进程已失去控制。
            } finally {
                shutdown();
            }
        }, "otakulog-desktop-parent");
        monitor.setDaemon(true);
        monitor.start();
    }

    public void shutdown() {
        var application = context;
        if (application == null || !closing.compareAndSet(false, true)) return;
        Thread closer = new Thread(application::close, "otakulog-desktop-shutdown");
        closer.setDaemon(false);
        closer.start();
    }
}
