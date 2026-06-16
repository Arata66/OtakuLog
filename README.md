# OtakuLog — 追番日记

一个基于 Spring Boot 的个人动漫追番管理系统，帮助你管理追番记录、追踪观看进度、发现新番剧。

## 核心功能

### 番剧管理
- 添加、编辑、删除追番记录（支持 Bangumi ID 去重）
- 进度追踪（上一集/下一集），到达最后一集自动标记完成
- 评分（0-10）与 Markdown 备注
- 三种视图：表格列表 / 详情卡片 / 封面画廊
- 批量操作（删除、改状态）
- 拖拽排序（SortableJS）
- 番剧分组（创建/查看/删除分组，分组内追番）

### Bangumi 集成
- 搜索 Bangumi 番剧数据库（名称/标签）
- 一键导入 Bangumi 用户收藏
- 自动匹配 Bangumi 链接（单个/批量）
- 查看作品详情、剧集列表、评分分布
- 当季新番 + 排行榜浏览
- 放送日历（我的/本季双视图）

### 数据分析
- 统计概览：总数/追中/完成/计划/放弃/进度/平均分
- 增强统计：年度对比/评分分布/标签统计/观看习惯
- 观看热力图（基于 episode_record 事件驱动，精确到集）
- 季度汇总 / 月度完成报告
- 追番时间线（按追番日期 / 开播日期）

### 以图搜番
- 基于 trace.moe API 的截图识别
- 上传图片即可匹配番剧名/集数/时间点

### 智能推荐
- 基于用户标签频率自动推荐 Bangumi 相似作品

### 数据同步
- JSON 导入/导出（支持名称匹配 + 增量导入）
- WebDAV 多设备同步（推送/拉取/状态检查）

### 分享功能
- 番剧分享卡（单部，带封面 + 评分 + 进度）
- 追番总结卡（含统计概览 + TOP 番剧）

### 界面特性
- 6 种预设主题色切换（靛蓝/翡翠/紫/珊瑚/琥珀/玫瑰）
- 深色/浅色模式
- 响应式设计，移动端适配
- 移动端底部导航栏 + 左右滑动切换 Tab
- 键盘快捷键（1-4 切换 Tab，/ 聚焦搜索，Esc 关闭弹窗）
- 封面图 IntersectionObserver 懒加载
- 骨架屏（表格/画廊/详情三种模式）
- PWA 支持（可安装到桌面 + Service Worker 离线缓存）
- 中英文国际化（i18n）
- Phosphor Icons 图标体系

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.0 | Web 框架 |
| Spring Data JPA | - | ORM |
| Spring Security | - | 表单登录认证 |
| Spring Cache + Caffeine | - | Bangumi API 响应缓存 |
| Flyway | 9.x | 数据库迁移（5 个版本） |
| Flyway MySQL | 9.x | MySQL 方言支持 |
| MySQL | 8.0+ | 生产数据库 |
| H2 | - | 测试环境内存数据库 |
| Thymeleaf | - | 服务端模板引擎 |
| Thymeleaf Spring Security | - | 模板层安全标签 |
| Springdoc OpenAPI | 2.3.0 | Swagger UI API 文档 |
| Lombok | - | 减少样板代码 |
| Jackson JSR310 | - | Java 8 时间序列化 |
| Maven | 3.6+ | 项目构建 |
| Java | 17+ | 开发语言 |

### 前端

| 技术 | 用途 |
|------|------|
| 原生 JavaScript（1668 行） | 单页应用逻辑 |
| Chart.js | 统计图表（7 个实例） |
| SortableJS | 拖拽排序 |
| Marked + DOMPurify | Markdown 渲染 |
| Phosphor Icons | 图标（CDN） |
| Outfit 字体 | Google Fonts |
| CSS 自定义属性 | 主题色/深色模式/进度条 |

### 测试

| 技术 | 用途 |
|------|------|
| JUnit 5 | 测试框架 |
| Mockito | Mock 框架 |
| Spring Security Test | 认证测试 |
| H2 | 测试内存数据库 |

## 项目结构

```
OtakuLog/
├── src/main/java/com/otakulog/
│   ├── OtakuLogApplication.java              # 应用入口
│   ├── common/
│   │   ├── ApiResponse.java                  # 统一 API 响应体
│   │   ├── GlobalExceptionHandler.java       # 全局异常处理（10 种异常映射）
│   │   ├── ResourceNotFoundException.java    # 资源不存在异常（→404）
│   │   └── ExternalApiException.java         # 外部 API 异常（→502）
│   ├── config/
│   │   ├── SecurityConfig.java               # Spring Security 表单登录
│   │   ├── CorsConfig.java                   # CORS 跨域配置
│   │   ├── CacheConfig.java                  # Caffeine 缓存配置
│   │   └── OpenApiConfig.java                # Swagger/OpenAPI 配置
│   ├── controller/
│   │   ├── AnimeController.java              # 番剧 CRUD + 统计 + 搜索
│   │   ├── BangumiApiController.java         # Bangumi 搜索/详情/导入
│   │   ├── GroupController.java              # 番剧分组管理
│   │   ├── LoginController.java              # 登录页
│   │   └── SyncApiController.java            # WebDAV 数据同步
│   ├── dto/                                  # 8 个数据传输对象
│   ├── entity/
│   │   ├── Anime.java                        # 番剧实体（21 字段）
│   │   ├── AnimeGroup.java                   # 分组实体
│   │   ├── EpisodeRecord.java                # 每集观看记录（热力图数据源）
│   │   └── BaseEntity.java                   # 基础实体（createdAt/updatedAt）
│   ├── enums/
│   │   └── AnimeStatus.java                  # WATCHING/FINISHED/PLANNING/DROPPED
│   ├── repository/
│   │   ├── AnimeRepository.java              # 番剧数据访问（~20 查询方法）
│   │   ├── AnimeGroupRepository.java         # 分组数据访问
│   │   └── EpisodeRecordRepository.java      # 观看记录数据访问
│   ├── service/
│   │   ├── AnimeService.java                 # 番剧服务接口
│   │   ├── BangumiService.java               # Bangumi 服务接口
│   │   ├── AiringScheduleService.java        # 放送时间表接口
│   │   ├── TraceMoeService.java              # 以图搜番接口
│   │   ├── WebDavSyncService.java            # WebDAV 同步接口
│   │   └── impl/
│   │       ├── AnimeServiceImpl.java         # 番剧服务实现（~880 行）
│   │       ├── BangumiServiceImpl.java       # Bangumi 服务实现（~360 行）
│   │       ├── AiringScheduleServiceImpl.java # 放送时间表实现
│   │       ├── TraceMoeServiceImpl.java      # 以图搜番实现
│   │       └── WebDavSyncServiceImpl.java    # WebDAV 同步实现
│   └── util/
│       └── SortUtil.java                     # 排序工具
├── src/main/resources/
│   ├── templates/
│   │   ├── anime.html                        # 主页面模板（~300 行）
│   │   └── login.html                        # 登录页
│   ├── static/
│   │   ├── css/
│   │   │   └── anime.css                     # 样式表（~950 行，CSS 变量体系）
│   │   ├── js/
│   │   │   ├── anime-app.js                  # 前端主逻辑（1668 行）
│   │   │   ├── i18n.js                       # 中英文国际化
│   │   │   └── share-card.js                 # Canvas 分享卡生成
│   │   ├── manifest.json                     # PWA 清单
│   │   └── sw.js                             # Service Worker
│   ├── db/migration/
│   │   ├── V1__baseline.sql                  # 初始表结构（anime 表）
│   │   ├── V2__add_indexes_and_audit.sql     # 索引 + 审计字段
│   │   ├── V3__add_watch_tracking.sql        # 观看追踪字段
│   │   ├── V4__add_anime_group.sql           # 番剧分组表
│   │   └── V5__add_episode_record.sql        # 每集观看记录表
│   └── application.properties                # 应用配置
├── src/test/java/com/otakulog/
│   ├── controller/
│   │   └── AnimeControllerTest.java          # Controller 层测试（8 用例）
│   ├── repository/
│   │   └── AnimeRepositoryTest.java          # Repository 层测试（8 用例）
│   └── service/
│       └── AnimeServiceImplTest.java         # Service 层测试（16 用例）
├── pom.xml
└── README.md
```

## 数据库表

| 表 | 用途 |
|----|------|
| `anime` | 核心番剧表（21 列，含 bangumi_id/legacy/watch_start_date） |
| `anime_group` | V4 新增，番剧分组 |
| `group_anime` | V4 新增，分组-番剧多对多关联 |
| `episode_record` | V5 新增，每集观看记录，驱动热力图 |

## 快速开始

### 前置要求

- Java 17 或更高版本
- Maven 3.6+
- MySQL 8.0+

### 安装步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/Arata66/OtakuLog.git
   cd OtakuLog
   ```

2. **创建数据库**
   ```sql
   CREATE DATABASE otaku_log CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

3. **配置数据库连接**

   编辑 `src/main/resources/application.properties`：
   ```properties
   spring.datasource.username=${DB_USER:root}
   spring.datasource.password=${DB_PASS:你的密码}
   ```

   默认连接 `localhost:3306/otaku_log`。

4. **编译并启动**
   ```bash
   mvn clean compile
   mvn spring-boot:run
   ```

5. **访问应用**

   打开浏览器访问：http://localhost:8080

   默认登录凭据：`admin` / `admin`（可通过环境变量 `ADMIN_USER` / `ADMIN_PASS` 修改）

### 运行测试

```bash
mvn test
```

测试使用 H2 内存数据库，无需 MySQL。32 个测试覆盖 Controller/Service/Repository 三层。

### API 文档

启动后访问 Swagger UI：http://localhost:8080/swagger-ui.html

## 配置说明

```properties
# 数据库
spring.datasource.url=jdbc:mysql://localhost:3306/otaku_log?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# 管理员凭据（支持环境变量）
app.admin.username=${ADMIN_USER:admin}
app.admin.password=${ADMIN_PASS:admin}

# JPA（生产环境使用 validate，由 Flyway 管理 schema）
spring.jpa.hibernate.ddl-auto=validate

# Flyway 数据库迁移
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true

# WebDAV 同步（可选）
otakulog.webdav.url=
otakulog.webdav.username=
otakulog.webdav.password=
otakulog.webdav.filename=otakulog_backup.json
```

## 项目约定

- 语言：中文对话、文档、注释
- 提交格式：`feat(scope): 描述` | `fix(scope): 描述` | `refactor(scope): 描述`
- 分支命名：`<用户名>/<功能描述>`
- 代码质量门禁：编译通过 + JS 语法检查 + 32 个测试全部通过 + 空白字符检查
- 详细约定见 [CLAUDE.md](CLAUDE.md)

## 许可证

MIT License
