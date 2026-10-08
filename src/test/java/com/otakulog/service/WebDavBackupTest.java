package com.otakulog.service;

import com.otakulog.service.impl.WebDavSyncServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WebDavBackupTest {
    private final BackupService backup = mock(BackupService.class);
    private WebDavSyncServiceImpl service;
    private MockRestServiceServer server;
    private final String url = "https://backup.example.test/otakulog_backup.json";

    @BeforeEach
    void 配置模拟远程服务() {
        service = new WebDavSyncServiceImpl(backup);
        RestClient.Builder builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "cachedClient", builder.build());
        ReflectionTestUtils.setField(service, "webdavUrl", "https://backup.example.test");
        ReflectionTestUtils.setField(service, "filename", "otakulog_backup.json");
    }

    @Test
    void 当推送时应该上传完整版本备份() {
        when(backup.exportJson()).thenReturn(BackupServiceTest.BACKUP);
        server.expect(requestTo(url)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(BackupServiceTest.BACKUP)).andRespond(withSuccess());
        assertEquals(true, service.push().get("success")); server.verify();
    }

    @Test
    void 当拉取预览时应该只校验而不恢复() {
        when(backup.previewJson(BackupServiceTest.BACKUP)).thenReturn(Map.of("valid", true, "created", 1));
        server.expect(requestTo(url)).andRespond(withSuccess(BackupServiceTest.BACKUP, MediaType.APPLICATION_JSON));
        var preview = service.previewPull();
        assertEquals(true, preview.get("valid")); assertEquals(64, preview.get("fingerprint").toString().length());
        verify(backup, never()).importJson(anyString()); server.verify();
    }

    @Test
    void 当确认后远程内容变化时应该拒绝恢复() {
        when(backup.previewJson(BackupServiceTest.BACKUP)).thenReturn(Map.of("valid", true));
        server.expect(requestTo(url)).andRespond(withSuccess(BackupServiceTest.BACKUP, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(BackupServiceTest.BACKUP + " ", MediaType.APPLICATION_JSON));
        String hash = service.previewPull().get("fingerprint").toString();
        assertThrows(IllegalArgumentException.class, () -> service.pull(hash));
        verify(backup, never()).importJson(anyString()); server.verify();
    }

    @Test
    void 当确认的远程内容未变时应该使用统一恢复服务() {
        when(backup.previewJson(BackupServiceTest.BACKUP)).thenReturn(Map.of("valid", true));
        when(backup.importJson(BackupServiceTest.BACKUP)).thenReturn(Map.of("created", 1, "updated", 0));
        server.expect(requestTo(url)).andRespond(withSuccess(BackupServiceTest.BACKUP, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(BackupServiceTest.BACKUP, MediaType.APPLICATION_JSON));
        assertEquals(1, service.pull(service.previewPull().get("fingerprint").toString()).get("created"));
        verify(backup).importJson(BackupServiceTest.BACKUP); server.verify();
    }
}
