package com.otakulog.service.impl;

import com.otakulog.common.ExternalApiException;
import com.otakulog.service.TraceMoeService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class TraceMoeServiceImpl implements TraceMoeService {

    private final RestClient client;

    public TraceMoeServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.client = RestClient.builder()
                .baseUrl("https://api.trace.moe")
                .requestFactory(factory)
                .build();
    }

    @Override
    public Map<String, Object> searchByImage(MultipartFile image) {
        try {
            byte[] imageBytes = image.getBytes();
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + image.getContentType() + ";base64," + base64;

            Map<String, Object> response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("url", dataUri)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.containsKey("result")) return null;

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("result");
            if (results == null || results.isEmpty()) return null;

            Map<String, Object> top = results.get(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filename", top.get("filename"));
            result.put("episode", top.get("episode"));
            result.put("from", top.get("from"));
            result.put("to", top.get("to"));
            Object similarity = top.get("similarity");
            if (similarity instanceof Number n) {
                result.put("confidence", Math.round(n.doubleValue() * 100));
            }
            result.put("image", top.get("image"));
            result.put("video", top.get("video"));

            String filename = (String) top.get("filename");
            if (filename != null && filename.contains(" - ")) {
                result.put("animeName", filename.substring(0, filename.indexOf(" - ")));
            } else if (filename != null) {
                result.put("animeName", filename);
            }

            List<Map<String, Object>> allResults = new ArrayList<>();
            for (Map<String, Object> r : results.subList(0, Math.min(5, results.size()))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", r.get("filename"));
                entry.put("episode", r.get("episode"));
                entry.put("from", r.get("from"));
                Object sim = r.get("similarity");
                if (sim instanceof Number n) entry.put("confidence", Math.round(n.doubleValue() * 100));
                entry.put("image", r.get("image"));
                String fn = (String) r.get("filename");
                if (fn != null && fn.contains(" - ")) {
                    entry.put("animeName", fn.substring(0, fn.indexOf(" - ")));
                } else {
                    entry.put("animeName", fn);
                }
                allResults.add(entry);
            }
            result.put("allResults", allResults);

            return result;
        } catch (Exception e) {
            throw new ExternalApiException("trace.moe 搜索失败: " + e.getMessage(), e);
        }
    }
}
