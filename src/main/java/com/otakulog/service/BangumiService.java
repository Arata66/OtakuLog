package com.otakulog.service;

import com.otakulog.dto.BangumiResult;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BangumiService {

    private final WebClient client = WebClient.builder()
            .baseUrl("https://api.bgm.tv/v0")
            .defaultHeader("User-Agent", "OtakuLog/1.0")
            .build();

    public List<BangumiResult> search(String keyword, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("sort", "match");
        body.put("filter", Map.of("type", List.of(2)));

        Map response = client.post()
                .uri(uriBuilder -> uriBuilder.path("/search/subjects").queryParam("limit", limit).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapResult).collect(Collectors.toList());
    }

    private BangumiResult mapResult(Map<String, Object> item) {
        BangumiResult r = new BangumiResult();
        r.setId(((Number) item.get("id")).intValue());
        r.setName((String) item.get("name"));
        r.setNameCn((String) item.getOrDefault("name_cn", ""));
        r.setImage((String) item.get("image"));
        r.setDate((String) item.get("date"));
        Object eps = item.get("eps");
        if (eps instanceof Number n) r.setEps(n.intValue());
        Object score = item.get("score");
        if (score instanceof Number n) r.setScore(n.doubleValue());
        return r;
    }
}
