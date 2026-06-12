package com.otakulog.service.impl;

import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.service.BangumiService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BangumiServiceImpl implements BangumiService {

    private final RestClient client;

    public BangumiServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.client = RestClient.builder()
                .baseUrl("https://api.bgm.tv")
                .defaultHeader("User-Agent", "OtakuLog/1.0")
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<BangumiResult> search(String keyword, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("sort", "match");
        body.put("filter", Map.of("type", List.of(2)));

        Map<String, Object> response = client.post()
                .uri(uriBuilder -> uriBuilder.path("/v0/search/subjects").queryParam("limit", limit).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapResult).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "bangumiSubject", key = "#subjectId")
    public BangumiSubjectDetail getSubject(int subjectId) {
        Map<String, Object> response = client.get()
                .uri("/v0/subjects/{id}", subjectId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

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

        Object rankObj = response.get("rank");
        if (rankObj instanceof Number n) detail.setRank(n.intValue());

        return detail;
    }

    @Override
    @Cacheable(value = "bangumiEpisodes", key = "#subjectId")
    @SuppressWarnings("unchecked")
    public List<BangumiEpisode> getSubjectEpisodes(int subjectId) {
        Map<String, Object> response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v0/episodes")
                        .queryParam("subject_id", subjectId)
                        .queryParam("limit", 200)
                        .queryParam("type", 0)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapEpisode).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "bangumiCalendar", unless = "#result.isEmpty()")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCalendar() {
        List<Map<String, Object>> response = client.get()
                .uri("/calendar")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> dayEntry : response) {
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
                    entry.put("airDate", item.get("air_date"));
                    entry.put("rank", item.get("rank"));
                    result.add(entry);
                }
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<BangumiResult> getSubjectRankings(String sort, int limit, int offset) {
        Map<String, Object> response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v0/subjects")
                        .queryParam("type", 2).queryParam("sort", sort)
                        .queryParam("limit", limit).queryParam("offset", offset).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.containsKey("data")) return List.of();
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapResult).collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<BangumiResult> getSeasonAnime(String sort, int limit, int offset) {
        // 使用 /calendar 端点获取当前季度新番
        List<Map<String, Object>> calendar = getCalendar();
        if (calendar.isEmpty()) return List.of();

        // 转换为 BangumiResult
        List<BangumiResult> results = calendar.stream().map(item -> {
            BangumiResult r = new BangumiResult();
            r.setId(((Number) item.get("id")).intValue());
            r.setName((String) item.get("name"));
            r.setNameCn((String) item.getOrDefault("nameCn", ""));
            r.setImage((String) item.get("image"));
            r.setDate((String) item.get("airDate"));
            Object eps = item.get("eps");
            if (eps instanceof Number n) r.setEps(n.intValue());
            Object score = item.get("score");
            if (score instanceof Number n) r.setScore(n.doubleValue());
            Object rank = item.get("rank");
            if (rank instanceof Number n) r.setRank(n.intValue());
            return r;
        }).collect(Collectors.toCollection(ArrayList::new));

        // 排序
        if ("rank".equals(sort)) {
            results.sort(Comparator.comparingInt(BangumiResult::getId));
        } else {
            results.sort(Comparator.comparing(BangumiResult::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        // 分页
        int from = Math.min(offset, results.size());
        int to = Math.min(from + limit, results.size());
        return results.subList(from, to);
    }

    @Override
    public List<BangumiResult> searchByTag(String tag, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", tag);
        body.put("sort", "heat");
        body.put("filter", Map.of("type", List.of(2)));

        Map<String, Object> response = client.post()
                .uri(uriBuilder -> uriBuilder.path("/v0/search/subjects").queryParam("limit", limit).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !response.containsKey("data")) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data.stream().map(this::mapResult).collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUserCollections(String username, int limit) {
        List<Map<String, Object>> allResults = new ArrayList<>();
        int offset = 0;
        int pageSize = Math.min(limit, 50);

        while (allResults.size() < limit) {
            Map<String, Object> response = client.get()
                    .uri("/v0/users/{username}/collections?subject_type=2&limit={limit}&offset={offset}",
                            username, pageSize, offset)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.containsKey("data")) break;

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) break;

            for (Map<String, Object> item : data) {
                Map<String, Object> entry = new LinkedHashMap<>();
                Object subject = item.get("subject");
                if (subject instanceof Map s) {
                    entry.put("subjectId", s.get("id"));
                    entry.put("name", s.get("name"));
                    entry.put("nameCn", s.getOrDefault("name_cn", ""));
                    Object images = s.get("images");
                    if (images instanceof Map imgMap) {
                        String imageUrl = (String) (imgMap.get("large") != null ? imgMap.get("large") :
                                imgMap.get("common") != null ? imgMap.get("common") : imgMap.get("medium"));
                        if (imageUrl != null && imageUrl.startsWith("//")) imageUrl = "https:" + imageUrl;
                        entry.put("image", imageUrl);
                    }
                    entry.put("eps", s.get("eps"));
                    entry.put("date", s.get("date"));
                } else {
                    entry.put("subjectId", item.get("subject_id"));
                }
                entry.put("type", item.get("type"));
                entry.put("epStatus", item.get("ep_status"));
                allResults.add(entry);

                if (allResults.size() >= limit) break;
            }

            offset += data.size();
            if (data.size() < pageSize) break;
        }

        return allResults;
    }

    private BangumiResult mapResult(Map<String, Object> item) {
        BangumiResult r = new BangumiResult();
        r.setId(((Number) item.get("id")).intValue());
        r.setName((String) item.get("name"));
        r.setNameCn((String) item.getOrDefault("name_cn", ""));
        Object imageObj = item.get("images");
        if (imageObj == null) imageObj = item.get("image");
        if (imageObj instanceof Map imgMap) {
            String url = (String) (imgMap.get("large") != null ? imgMap.get("large") :
                    imgMap.get("common") != null ? imgMap.get("common") : imgMap.get("medium"));
            if (url != null && url.startsWith("//")) url = "https:" + url;
            r.setImage(url);
        } else if (imageObj instanceof String s) {
            r.setImage(s);
        }
        r.setDate((String) item.get("date"));
        Object eps = item.get("eps");
        if (eps instanceof Number n) r.setEps(n.intValue());
        Object score = item.get("score");
        if (score instanceof Number n) r.setScore(n.doubleValue());
        else {
            Object ratingObj = item.get("rating");
            if (ratingObj instanceof Map rating) {
                Object s = rating.get("score");
                if (s instanceof Number n) r.setScore(n.doubleValue());
            }
        }
        Object rankObj = item.get("rank");
        if (rankObj instanceof Number n) r.setRank(n.intValue());
        else {
            Object ratingObj = item.get("rating");
            if (ratingObj instanceof Map rating) {
                Object rnk = rating.get("rank");
                if (rnk instanceof Number n) r.setRank(n.intValue());
            }
        }
        return r;
    }

    private BangumiEpisode mapEpisode(Map<String, Object> item) {
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
