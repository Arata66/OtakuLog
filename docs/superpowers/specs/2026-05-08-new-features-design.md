# 新功能设计文档

## 1. 自定义主题色

### 设计
- 预设 6 个配色方案，每个方案覆盖 `--primary`、`--primary-soft`、`--primary-mid`、`--primary-light` 四个 CSS 变量
- 用户点击色块切换主题，存入 localStorage
- 页面加载时从 localStorage 恢复

### 预设方案
| 名称 | 主色 | 软色 |
|------|------|------|
| 靛蓝（默认）| #122e8a | rgba(18,46,138,0.08) |
| 翡翠 | #1a7a4a | rgba(26,122,74,0.08) |
| 紫罗兰 | #6a3a8a | rgba(106,58,138,0.08) |
| 珊瑚 | #c44a3a | rgba(196,74,58,0.08) |
| 琥珀 | #8a6a1a | rgba(138,106,26,0.08) |
| 玫瑰 | #8a3a5a | rgba(138,58,90,0.08) |

### UI
- 在 header 右侧添加一个调色板图标按钮
- 点击弹出 6 个圆形色块，点击切换
- 当前选中的色块显示勾选标记

### 涉及文件
- `src/main/resources/static/css/anime.css` — 添加 CSS 变量方案
- `src/main/resources/static/js/anime-app.js` — 主题切换逻辑
- `src/main/resources/templates/anime.html` — 添加色板 UI

---

## 2. 观看热力图

### 设计
- 类似 GitHub 贡献图，展示每天的观看活动
- 数据来源：番剧的 `watchStartDate`、`endDate`、`createdAt`，以及上一集/下一集操作时间
- 由于当前没有记录每次观看操作的时间戳，使用简化方案：基于番剧的 `watchStartDate` 到 `endDate` 期间均匀分布观看活动

### 数据方案
- 后端新增 API：`GET /api/anime/heatmap` 返回过去一年每天的观看集数
- 计算逻辑：对于每部番剧，从 watchStartDate 到 endDate（或今天），将 currentEpisode 均匀分布到这些天

### UI
- 在图表 tab 中添加热力图卡片
- 使用 Canvas 或纯 CSS 网格渲染 52 周 x 7 天的热力图
- 颜色深浅表示观看强度（0/1-2/3-5/6+集）
- 鼠标悬停显示日期和集数

### 涉及文件
- `src/main/java/com/otakulog/controller/AnimeController.java` — 新增端点
- `src/main/java/com/otakulog/service/AnimeService.java` — 新增方法
- `src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java` — 实现
- `src/main/resources/templates/anime.html` — 热力图容器
- `src/main/resources/static/js/anime-app.js` — 渲染逻辑
- `src/main/resources/static/css/anime.css` — 热力图样式

---

## 3. 相似番剧推荐

### 设计
- 在详情弹窗的 Bangumi 详情区，当加载 Bangumi 详情后，用该番剧的标签搜索相似番剧
- 复用现有 `BangumiService.searchByTag` 方法
- 过滤掉用户已追踪的番剧

### UI
- 在弹窗的 Bangumi 详情区底部，添加「相似番剧」卡片网格
- 显示封面、名称、评分
- 点击可跳转到 Bangumi 搜索添加

### 涉及文件
- `src/main/java/com/otakulog/controller/BangumiApiController.java` — 新增端点
- `src/main/resources/static/js/anime-app.js` — 弹窗中加载相似推荐

---

## 4. 番剧分组/收藏夹

### 设计
- 新增 `AnimeGroup` 实体：id, name, description, color, sortOrder
- 新增 `anime_group_relation` 关联表：group_id, anime_id
- 一个番剧可以属于多个分组

### 功能
- 创建/编辑/删除分组
- 将番剧添加到分组（支持批量）
- 按分组筛选番剧列表
- 分组管理界面

### 涉及文件
- `src/main/java/com/otakulog/entity/AnimeGroup.java` — 新实体
- `src/main/java/com/otakulog/entity/AnimeGroupRelation.java` — 关联实体
- `src/main/java/com/otakulog/repository/AnimeGroupRepository.java`
- `src/main/java/com/otakulog/controller/GroupController.java`
- `src/main/resources/db/migration/` — Flyway 迁移
- `src/main/resources/templates/anime.html` — 分组 UI
- `src/main/resources/static/js/anime-app.js` — 分组交互
- `src/main/resources/static/css/anime.css` — 分组样式

---

## 5. 分享卡片

### 设计
- 使用 Canvas API 生成精美的追番总结卡片
- 可选卡片类型：单部番剧详情卡、追番总结卡（统计概览）
- 生成 PNG 图片供下载或复制

### 卡片内容
**单部番剧卡：**
- 封面图、名称、评分、状态、集数进度
- 标签、观看时长
- OtakuLog 水印

**追番总结卡：**
- 总追番数、平均评分、总集数
- 最高分番剧 TOP 3
- 观看时长统计
- OtakuLog 水印

### UI
- 在详情弹窗添加「分享」按钮
- 在统计区域添加「生成总结卡」按钮
- 点击后 Canvas 渲染 → 显示预览 → 下载/复制

### 涉及文件
- `src/main/resources/static/js/share-card.js` — 新文件，Canvas 渲染逻辑
- `src/main/resources/static/css/anime.css` — 分享按钮样式
- `src/main/resources/templates/anime.html` — 添加按钮
- `src/main/resources/static/js/anime-app.js` — 触发逻辑
