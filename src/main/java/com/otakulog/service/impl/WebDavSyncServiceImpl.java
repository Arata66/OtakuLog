package com.otakulog.service.impl;

import com.otakulog.common.ExternalApiException;
import com.otakulog.service.BackupService;
import com.otakulog.service.WebDavSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class WebDavSyncServiceImpl implements WebDavSyncService {

    private final BackupService backup;

    public WebDavSyncServiceImpl(BackupService backup) {
        this.backup = backup;
    }

    @Value("${otakulog.webdav.url:}")
    private String webdavUrl;

    @Value("${otakulog.webdav.username:}")
    private String username;

    @Value("${otakulog.webdav.password:}")
    private String password;

    @Value("${otakulog.webdav.filename:otakulog_backup.json}")
    private String filename;

    private String lastSyncTime = null;
    private String lastSyncType = null;

    private volatile RestClient cachedClient;

    private RestClient buildClient() {
        if (cachedClient != null) return cachedClient;
        synchronized (this) {
            if (cachedClient != null) return cachedClient;
            // 固定认证编码，避免 Windows 与服务器默认字符集不同导致凭据变化。
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(10_000);
            cachedClient = RestClient.builder()
                    .defaultHeader("Authorization", "Basic " + auth)
                    .requestFactory(factory)
                    .build();
            return cachedClient;
        }
    }

    @Override
    public Map<String, Object> push() {
        validateConfig();
        String json = backup.exportJson();
        String fullUrl = webdavUrl.endsWith("/") ? webdavUrl + filename : webdavUrl + "/" + filename;

        buildClient().put()
                .uri(fullUrl)
                .header("Content-Type", "application/json")
                .body(json)
                .retrieve()
                .toBodilessEntity();

        lastSyncTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        lastSyncType = "push";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已推送到 WebDAV");
        result.put("time", lastSyncTime);
        return result;
    }

    @Override
    public Map<String, Object> pull() {
        return pull(null);
    }

    @Override
    public Map<String, Object> previewPull() {
        String json = download();
        Map<String, Object> result = new LinkedHashMap<>(backup.previewJson(json));
        result.put("fingerprint", fingerprint(json));
        return result;
    }

    private String download() {
        validateConfig();
        String fullUrl = webdavUrl.endsWith("/") ? webdavUrl + filename : webdavUrl + "/" + filename;

        String json = buildClient().get()
                .uri(fullUrl)
                .retrieve()
                .body(String.class);

        if (json == null || json.isEmpty()) {
            throw new ExternalApiException("WebDAV 文件为空或不存在");
        }

        return json;
    }

    private String fingerprint(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("无法计算备份摘要", e); }
    }

    @Override
    public Map<String, Object> pull(String fingerprint) {
        String json = download();
        if (fingerprint != null && !fingerprint(json).equals(fingerprint))
            throw new IllegalArgumentException("远程备份已变化，请重新预览后再恢复");
        Map<String, Object> importResult = backup.importJson(json);

        lastSyncTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        lastSyncType = "pull";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已从 WebDAV 拉取");
        result.put("time", lastSyncTime);
        result.put("created", importResult.get("created"));
        result.put("updated", importResult.get("updated"));
        return result;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", !webdavUrl.isEmpty());
        status.put("url", webdavUrl.isEmpty() ? null : webdavUrl);
        status.put("lastSyncTime", lastSyncTime);
        status.put("lastSyncType", lastSyncType);

        if (!webdavUrl.isEmpty()) {
            try {
                String fullUrl = webdavUrl.endsWith("/") ? webdavUrl : webdavUrl + "/";
                buildClient().method(HttpMethod.HEAD)
                        .uri(fullUrl)
                        .retrieve()
                        .toBodilessEntity();
                status.put("connected", true);
            } catch (Exception e) {
                status.put("connected", false);
                status.put("error", e.getMessage());
            }
        }

        return status;
    }

    private void validateConfig() {
        if (webdavUrl.isEmpty()) {
            throw new ExternalApiException("WebDAV 未配置，请在 application.properties 中设置 otakulog.webdav.url");
        }
    }
}
