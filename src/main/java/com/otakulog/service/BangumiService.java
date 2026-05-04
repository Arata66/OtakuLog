package com.otakulog.service;

import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BangumiService {

    private final WebClient client = WebClient.builder()
            .baseUrl("https://api.bgm.tv")
            .defaultHeader("User-Agent", "OtakuLog/1.0")
            .build();

    public List<BangumiResult> search(String keyword, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("sort", "match");
        body.put("filter", Map.of("type", List.of(2)));

        Map response = client.post()
                .uri(uriBuilder -> uriBuilder.path("/v0/search/subjects").queryParam("limit", limit).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapResult).collect(Collectors.toList());
    }

    @Cacheable(value = "bangumiSubject", key = "#subjectId")
    public BangumiSubjectDetail getSubject(int subjectId) {
        Map response = client.get()
                .uri("/v0/subjects/{id}", subjectId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null) return null;

        BangumiSubjectDetail detail = new BangumiSubjectDetail();
        detail.setId(((Number) response.get("id")).intValue());
        detail.setName((String) response.get("name"));
        detail.setNameCn((String) response.getOrDefault("name_cn", ""));
        detail.setSummary((String) response.getOrDefault("summary", ""));
        detail.setAirDate((String) response.get("date"));
        detail.setTotalEpisodes(response.get("eps") instanceof Number ? ((Number) response.get("eps")).intValue() : null);

        Object images = response.get("images");
        if (images instanceof Map imgMap) {
            String imageUrl = (String) imgMap.get("large");
            if (imageUrl == null) imageUrl = (String) imgMap.get("common");
            if (imageUrl == null) imageUrl = (String) imgMap.get("medium");
            if (imageUrl != null && imageUrl.startsWith("//")) imageUrl = "https:" + imageUrl;
            if (imageUrl != null) detail.setCoverUrl(imageUrl);
        }

        Object tagsObj = response.get("tags");
        if (tagsObj instanceof List<?> tags) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tagList = (List<Map<String, Object>>) (List<?>) tags;
            detail.setTags(tagList.stream().limit(10).collect(Collectors.toList()));
        }

        Object platformObj = response.get("platform");
        if (platformObj instanceof Map platform) {
            detail.setPlatform((String) platform.get("name"));
        } else if (platformObj instanceof String ps) {
            detail.setPlatform(ps);
        }

        Object ratingObj = response.get("rating");
        if (ratingObj instanceof Map rating) {
            Object score = rating.get("score");
            if (score instanceof Number n) detail.setRating(n.doubleValue());
            Object count = rating.get("total");
            if (count instanceof Number n) detail.setRatingCount(n.intValue());
            detail.setRatingDetails(rating);
        }

        return detail;
    }

    @Cacheable(value = "bangumiEpisodes", key = "#subjectId")
    @SuppressWarnings("unchecked")
    public List<BangumiEpisode> getSubjectEpisodes(int subjectId) {
        Map response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v0/episodes")
                        .queryParam("subject_id", subjectId)
                        .queryParam("limit", 200)
                        .queryParam("type", 0)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapEpisode).collect(Collectors.toList());
    }

    @Cacheable("bangumiCalendar")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCalendar() {
        List<Map> response = client.get()
                .uri("/calendar")
                .retrieve()
                .bodyToFlux(Map.class)
                .collectList()
                .block();

        if (response == null) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map dayEntry : response) {
            Object weekdayObj = dayEntry.get("weekday");
            int weekday;
            if (weekdayObj instanceof Map) {
                weekday = ((Number) ((Map) weekdayObj).get("id")).intValue();
            } else {
                weekday = ((Number) weekdayObj).intValue();
            }
            List<Map<String, Object>> items = (List<Map<String, Object>>) dayEntry.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("weekday", weekday);
                    entry.put("id", item.get("id"));
                    entry.put("name", item.get("name"));
                    entry.put("nameCn", item.getOrDefault("name_cn", ""));
                    // images is an object {large, common, medium, small, grid}
                    Object images = item.get("images");
                    String imageUrl = null;
                    if (images instanceof Map imgMap) {
                        Object large = imgMap.get("large");
                        Object common = imgMap.get("common");
                        Object medium = imgMap.get("medium");
                        imageUrl = (String) (large != null ? large : common != null ? common : medium);
                    }
                    if (imageUrl != null && imageUrl.startsWith("//")) {
                        imageUrl = "https:" + imageUrl;
                    }
                    entry.put("image", imageUrl);
                    Map<String, Object> rating = (Map<String, Object>) item.get("rating");
                    if (rating != null) {
                        entry.put("score", rating.get("score"));
                    }
                    entry.put("eps", item.get("eps"));
                    entry.put("airDate", item.get("date"));
                    result.add(entry);
                }
            }
        }
        return result;
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

    private BangumiEpisode mapEpisode(Map item) {
        BangumiEpisode ep = new BangumiEpisode();
        ep.setId(((Number) item.get("id")).intValue());
        ep.setName((String) item.getOrDefault("name", ""));
        ep.setNameCn((String) item.getOrDefault("name_cn", ""));
        ep.setAirdate((String) item.getOrDefault("airdate", ""));
        Object sort = item.get("sort");
        if (sort instanceof Number n) ep.setSort(n.intValue());
        Object duration = item.get("duration");
        if (duration instanceof Number n) ep.setDuration(n.intValue());
        ep.setDesc((String) item.getOrDefault("desc", ""));
        return ep;
    }
}
