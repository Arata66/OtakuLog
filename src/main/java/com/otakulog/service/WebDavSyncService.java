package com.otakulog.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WebDavSyncService {

    @Autowired
    private AnimeService animeService;

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

    public Map<String, Object> push() {
        validateConfig();
        String json = animeService.exportJson();
        String fullUrl = webdavUrl.endsWith("/") ? webdavUrl + filename : webdavUrl + "/" + filename;

        WebClient client = buildClient();
        client.put()
                .uri(fullUrl)
                .header("Content-Type", "application/json")
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .block();

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

        WebClient client = buildClient();
        String json = client.get()
                .uri(fullUrl)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (json == null || json.isEmpty()) {
            throw new RuntimeException("WebDAV 文件为空或不存在");
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
                WebClient client = buildClient();
                String fullUrl = webdavUrl.endsWith("/") ? webdavUrl : webdavUrl + "/";
                client.method(org.springframework.http.HttpMethod.HEAD)
                        .uri(fullUrl)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                status.put("connected", true);
            } catch (Exception e) {
                status.put("connected", false);
                status.put("error", e.getMessage());
            }
        }

        return status;
    }

    private WebClient buildClient() {
        String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        return WebClient.builder()
                .defaultHeader("Authorization", "Basic " + auth)
                .build();
    }

    private void validateConfig() {
        if (webdavUrl.isEmpty()) {
            throw new RuntimeException("WebDAV 未配置，请在 application.properties 中设置 otakulog.webdav.url");
        }
    }
}
