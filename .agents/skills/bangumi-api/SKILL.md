---
name: bangumi-api
description: Bangumi API 集成参考 — 搜索、详情、剧集、日历、排行榜、用户收藏、以图搜番。当需要修改 Bangumi 相关功能、新增 API 调用、调试 Bangumi 数据问题、或理解番剧搜索/导入流程时使用。
---

# Bangumi API 集成指南

本项目通过 Bangumi API (api.bgm.tv) 获取番剧数据，通过 trace.moe API 实现以图搜番。

## 架构

- `BangumiService` — 封装所有 Bangumi API 调用，使用 Spring RestClient，超时 10s/30s
- `TraceMoeService` — 封装 trace.moe 以图搜番
- `BangumiApiController` — REST 端点，所有异常返回 502 + `ApiResponse.error()`
- 缓存：Spring Cache + Caffeine（500 条目，30 分钟 TTL）

## API 端点

### Bangumi (api.bgm.tv)

| 功能 | 方法 | 端点 | 缓存名 |
|------|------|------|--------|
| 搜索 | POST | `/v0/search/subjects` | 无 |
| 作品详情 | GET | `/v0/subjects/{id}` | `bangumiSubject` |
| 剧集列表 | GET | `/v0/episodes?subject_id=&type=0&limit=200` | `bangumiEpisodes` |
| 放送日历 | GET | `/calendar` | `bangumiCalendar` |
| 排行榜 | GET | `/v0/subjects?type=2&sort=rank` | 无 |
| 用户收藏 | GET | `/v0/users/{username}/collections?subject_type=2` | 无 |

### trace.moe

| 功能 | 方法 | 端点 |
|------|------|------|
| 以图搜番 | GET | `/search?url=dataUri` |

## 关键实现细节

1. **搜索是 POST 请求**：body 含 `{keyword, sort: "match", filter: {type: [2]}}`，type=2 表示只搜动画
2. **图片字段两种格式**：`/v0/subjects` 返回 images 对象，`/v0/search/subjects` 可能返回 image 字符串，`mapResult()` 兼容两种
3. **图片协议**：返回的 URL 可能以 `//` 开头，需补 `https:`
4. **收藏导入状态映射**：`1=wish→PLANNING, 2=watched→FINISHED, 3=watching→WATCHING, 5=dropped→DROPPED`
5. **以图搜番**：图片先转 Base64 data URI，再通过 URL 参数传递

## 修改指南

- **新增 API 调用**：在 `BangumiService` 添加方法，用 `@Cacheable(value="bangumiXxx", key="#id")` 标注缓存
- **新增端点**：在 `BangumiApiController` 添加，返回 `ResponseEntity<ApiResponse<T>>`，catch 异常返回 502
- **DTO 映射**：参考 `mapResult()` / `mapEpisode()` 处理 Bangumi 响应格式
- **清除缓存**：注入 `CacheManager`，调用 `cache.evict(key)`
