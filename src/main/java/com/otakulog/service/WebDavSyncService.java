package com.otakulog.service;

import com.otakulog.common.ExternalApiException;
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

@Service
public class WebDavSyncService {

    private final AnimeService animeService;

    public WebDavSyncService(AnimeService animeService) {
        this.animeService = animeService;
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
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
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

    public Map<String, Object> push() {
        validateConfig();
        String json = animeService.exportJson();
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

    public Map<String, Object> pull() {
        validateConfig();
        String fullUrl = webdavUrl.endsWith("/") ? webdavUrl + filename : webdavUrl + "/" + filename;

        String json = buildClient().get()
                .uri(fullUrl)
                .retrieve()
                .body(String.class);

        if (json == null || json.isEmpty()) {
            throw new ExternalApiException("WebDAV 文件为空或不存在");
        }

        Map<String, Object> importResult = animeService.importJson(json);

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
