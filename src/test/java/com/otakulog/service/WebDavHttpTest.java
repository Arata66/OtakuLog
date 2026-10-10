package com.otakulog.service;

import com.otakulog.common.ExternalApiException;
import com.otakulog.service.impl.WebDavSyncServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebDavHttpTest {
    private final BackupService backup = mock(BackupService.class);
    private final AtomicReference<String> file = new AtomicReference<>(BackupServiceTest.BACKUP);
    private final AtomicReference<String> uploaded = new AtomicReference<>();
    private HttpServer server;
    private WebDavSyncServiceImpl service;
    private volatile String credentials = "demo:fictional";
    private volatile int failure = 0;

    @BeforeEach
    void 启动隔离HTTP服务() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String expected = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401, -1); return; }
                if (failure != 0) { exchange.sendResponseHeaders(failure, -1); return; }
                if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); return; }
                if (exchange.getRequestMethod().equals("PUT")) {
                    uploaded.set(body);
                    exchange.sendResponseHeaders(201, -1); return;
                }
                byte[] bytes = file.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); service = new WebDavSyncServiceImpl(backup);
        ReflectionTestUtils.setField(service, "webdavUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/dav");
        ReflectionTestUtils.setField(service, "filename", "fixture.json");
        ReflectionTestUtils.setField(service, "username", "demo"); ReflectionTestUtils.setField(service, "password", "fictional");
        when(backup.exportJson()).thenReturn(BackupServiceTest.BACKUP);
        when(backup.previewJson(anyString())).thenReturn(Map.of("valid", true));
    }
    @AfterEach
    void 停止本次HTTP服务() { server.stop(0); }
    @Test
    void 当凭据含中文时应该发送UTF8认证并保留备份原始内容() {
        credentials = "虚构用户:虚构口令";
        ReflectionTestUtils.setField(service, "username", "虚构用户"); ReflectionTestUtils.setField(service, "password", "虚构口令");
        assertEquals(true, service.push().get("success")); assertEquals(BackupServiceTest.BACKUP, uploaded.get());
        assertEquals("push", service.getStatus().get("lastSyncType"));
    }
    @Test
    void 当目录可访问时应该报告已连接而不读取或恢复备份() {
        assertEquals(true, service.getStatus().get("connected")); verifyNoInteractions(backup);
    }
    @Test
    void 当远程备份只做预览时应该返回摘要且不写入() {
        assertEquals(64, service.previewPull().get("fingerprint").toString().length());
        verify(backup, never()).importJson(anyString()); assertNull(service.getStatus().get("lastSyncTime"));
    }
    @Test
    void 当预览后远程字节变化时应该拒绝恢复且不更新同步时间() {
        String digest = service.previewPull().get("fingerprint").toString(); file.set(file.get() + " ");
        assertThrows(IllegalArgumentException.class, () -> service.pull(digest));
        verify(backup, never()).importJson(anyString()); assertNull(service.getStatus().get("lastSyncTime"));
    }
    @Test
    void 当认证或服务失败时应该不报告同步成功且可重试() {
        failure = 401; assertThrows(RestClientException.class, service::push);
        failure = 503; assertThrows(RestClientException.class, () -> service.pull("摘要"));
        assertNull(service.getStatus().get("lastSyncTime")); verify(backup, never()).importJson(anyString());
        failure = 0; assertEquals(true, service.push().get("success"));
    }
    @Test
    void 当远程文件为空时应该拒绝恢复而不是报告成功() {
        file.set(""); assertThrows(ExternalApiException.class, service::previewPull); verify(backup, never()).importJson(anyString());
    }
}
