package com.otakulog.service.impl;

import com.otakulog.dto.BangumiEpisode;
import com.otakulog.common.ExternalApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    public BangumiServiceImpl() { this("https://api.bgm.tv"); }

    @Autowired
    public BangumiServiceImpl(@Value("${otakulog.bangumi.base-url:https://api.bgm.tv}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
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

            if (response == null || !(response.get("data") instanceof List<?> data))
                throw new ExternalApiException("Bangumi 收藏响应格式异常，请稍后重试");
            if (data.isEmpty()) break;

            for (Object row : data) {
                if (!(row instanceof Map<?, ?> item))
                    throw new ExternalApiException("Bangumi 收藏条目格式异常，请稍后重试");
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
                entry.put("rate", item.get("rate"));
                entry.put("comment", item.get("comment"));
                entry.put("tags", item.get("tags"));
                entry.put("sourceUpdatedAt", item.get("updated_at"));
                allResults.add(entry);

                if (allResults.size() >= limit) break;
            }

            offset += data.size();
            if (data.size() < pageSize) break;
        }

        return allResults;
    }

    @Override
    public com.otakulog.dto.BangumiCollectionSnapshot getCompleteUserCollections(String username) {
        var rows = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<Integer>();
        int firstTotal = -1, latestTotal = 0, offset = 0;
        boolean changed = false;
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
        for (int page = 0; page < 200; page++) {
            if (System.nanoTime() > deadline) throw new ExternalApiException("收藏读取超时，未导入任何作品，请稍后重试");
            Map<String, Object> response = client.get()
                    .uri("/v0/users/{username}/collections?subject_type=2&limit=50&offset={offset}", username, offset)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (response == null || !(response.get("data") instanceof List<?> data)
                    || !(response.get("total") instanceof Number total) || total.doubleValue() != total.intValue()
                    || total.intValue() < 0 || total.intValue() > 10000 || data.size() > 50)
                throw new ExternalApiException("收藏响应异常或超过10000条保护上限，未生成完整预览");
            latestTotal = total.intValue();
            if (firstTotal < 0) firstTotal = latestTotal;
            changed |= latestTotal != firstTotal;
            for (Object value : data) {
                if (!(value instanceof Map<?, ?> item)) throw new ExternalApiException("收藏条目格式异常");
                var entry = collectionRow(item);
                Object id = entry.get("subjectId");
                if (!(id instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() <= 0)
                    throw new ExternalApiException("收藏缺少有效作品编号，请重新读取");
                if (!ids.add(number.intValue())) throw new ExternalApiException("分页出现重复作品，收藏可能已变化，请重新读取");
                rows.add(entry);
            }
            offset += data.size();
            if (offset >= latestTotal) {
                if (offset != latestTotal) throw new ExternalApiException("收藏数量与响应总数不一致，请重新读取");
                return new com.otakulog.dto.BangumiCollectionSnapshot(latestTotal, rows, changed);
            }
            if (data.size() < 50) throw new ExternalApiException("收藏分页提前结束，未生成完整预览，请重新读取");
        }
        throw new ExternalApiException("收藏读取达到保护上限，未导入任何作品");
    }

    private Map<String, Object> collectionRow(Map<?, ?> item) {
        var entry = new LinkedHashMap<String, Object>();
        Object subject = item.get("subject");
        if (subject instanceof Map<?, ?> s) {
            entry.put("subjectId", s.get("id")); entry.put("name", s.get("name")); entry.put("nameCn", s.get("name_cn"));
            entry.put("eps", s.get("eps")); entry.put("date", s.get("date"));
            if (s.get("images") instanceof Map<?, ?> images) {
                Object image = images.get("large") != null ? images.get("large") : images.get("common") != null ? images.get("common") : images.get("medium");
                if (image instanceof String url) entry.put("image", url.startsWith("//") ? "https:" + url : url);
            }
        } else entry.put("subjectId", item.get("subject_id"));
        entry.put("type", item.get("type")); entry.put("epStatus", item.get("ep_status")); entry.put("rate", item.get("rate"));
        entry.put("comment", item.get("comment")); entry.put("tags", item.get("tags")); entry.put("sourceUpdatedAt", item.get("updated_at"));
        return entry;
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
