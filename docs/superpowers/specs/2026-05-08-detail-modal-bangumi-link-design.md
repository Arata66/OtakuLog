# 番剧详情弹窗优化设计

## 概述

优化番剧详情弹窗，解决两个问题：
1. 老番剧缺少 Bangumi 链接，无法显示 Bangumi 详情
2. 弹窗 UI 布局改进，增加 Bangumi 外链

## 功能 1：批量匹配 Bangumi 链接

### 问题

老番剧在添加时未关联 Bangumi，`bangumiId` 为 null。弹窗中 `if (a.bangumiId) loadBangumiDetail(a.bangumiId)` 跳过了这些番剧。

### 方案

#### 后端

新增 API 端点：

```
POST /api/anime/match-bangumi/{id}
```

- 用该番剧的 `name` 调用 `BangumiService.search(name, 5)`
- 从结果中找名称完全匹配（忽略大小写）的第一条
- 若匹配成功，更新 `anime.bangumiId` 并返回更新后的 `AnimeVO`
- 若未匹配，返回空结果，前端提示用户手动搜索

新增批量端点：

```
POST /api/anime/batch-match-bangumi
```

- 查找所有 `bangumiId IS NULL` 的番剧
- 逐个调用匹配逻辑（带限流，避免 Bangumi API 过载）
- 返回匹配结果摘要：`{ matched: 5, failed: 3, total: 8 }`

#### 前端

- 弹窗中，当 `bangumiId` 为空时，在 `#bangumiDetailSection` 区域显示「匹配 Bangumi」按钮
- 点击后调用单集匹配 API，成功后刷新弹窗内容
- 在设置区域添加「一键补全 Bangumi 链接」按钮，调用批量端点

### 限流策略

- 批量匹配时，每个请求间隔 500ms，避免 Bangumi API 限流
- 前端显示进度条

## 功能 2：弹窗 UI 优化

### 当前布局

```
+---------------------------+
|     封面图 (16:9 横版)      |
|     状态标签 (右上角)        |
+---------------------------+
| 标题                        |
| 季度 | 评分                  |
| 状态 | 集数 | 开播 | 完结    |
| 进度条                      |
| 备注                        |
| Bangumi 详情区              |
| [编辑] [上一集] [下一集] [删除]|
+---------------------------+
```

### 新布局

```
+---------------------------+
| +------+  标题             |
| |      |  季度 | 评分      |
| | 封面 |  状态 | 集数      |
| | 竖版 |  开播 | 完结      |
| +------+  [Bangumi 查看]   |
+---------------------------+
| 进度条                      |
| 备注                        |
| Bangumi 详情区              |
| [编辑] [上一集] [下一集] [删除]|
+---------------------------+
```

### 改动点

1. **封面图**：从 16:9 横版改为左侧竖版海报（aspect-ratio: 2/3）
2. **头部区域**：改为 flex 布局，左侧封面 + 右侧信息
3. **Bangumi 外链**：在信息区下方添加「在 Bangumi 查看」按钮，链接到 `bgm.tv/subject/{bangumiId}`
4. **响应式**：移动端保持上下布局（封面在上，信息在下）

## 涉及文件

- `src/main/java/com/otakulog/controller/AnimeController.java` — 新增匹配端点
- `src/main/java/com/otakulog/service/AnimeService.java` — 新增匹配逻辑
- `src/main/java/com/otakulog/repository/AnimeRepository.java` — 新增查询方法
- `src/main/resources/static/js/anime-app.js` — 弹窗 UI 和匹配按钮
- `src/main/resources/static/css/anime.css` — 弹窗样式调整
