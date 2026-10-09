# OtakuLog — 追番日记

一个基于 Spring Boot 的个人动漫追番管理系统，帮助你管理追番记录、追踪观看进度、发现新番剧。

## 核心功能

### 番剧管理
- 添加、编辑、删除追番记录（支持 Bangumi ID / 名称双重去重）
- 进度追踪（上一集/下一集），到达最后一集自动标记完成，退回第 0 集恢复计划
- 评分（0-10）与 Markdown 备注
- 三种视图：表格列表 / 详情卡片 / 封面画廊（带 View Toggle 图标切换）
- 批量操作（删除、改状态、标记完成）
- 拖拽排序（SortableJS），排序持久化到后端
- 番剧分组（创建/查看/删除分组，分组内管理番剧）
- 数据分页查询
- 详情内逐集观看记录、来源核对与日期补录（保留未知日期，保存冲突保护）
- 首页继续观看与今日放送参考，支持直接记进度、同作品防重复点击和详情联动

### Bangumi 集成
- 搜索 Bangumi 番剧数据库（名称/标签）
- 一键导入 Bangumi 用户收藏
- 自动匹配 Bangumi 链接（单个/批量）
- 查看作品详情、剧集列表、评分分布
- 当季新番 + 排行榜浏览
- 放送日历（我的/本季双视图）

### 数据分析
- 统计概览：总数/追中/完成/计划/放弃/进度/平均分
- 增强统计：年度对比 / 评分分布 / 标签统计 / 观看习惯
- 年度报告：完成数与逐集观看量分开、有效评分均值、来源与缺失说明、明确标记估算时长
- 观看热力图（按逐集已知日期聚合，日期未知的补录不生成观看量；已有历史估算日期保留）
- 季度汇总 / 月度完成报告
- 追番时间线（按追番日期 / 开播日期）

### 以图搜番
- 基于 trace.moe API 的截图识别
- 上传图片即可匹配番剧名/集数/时间点

### 智能推荐
- 基于用户标签频率自动推荐 Bangumi 相似作品

### 数据同步
- 完整 JSON 备份恢复（逐集日期与来源、标签、分组及关联，支持预览、冲突拒绝与原子合并）
- WebDAV 手动备份与合并恢复（推送/拉取预览/摘要确认/状态检查）

### 分享功能
- 番剧分享卡（单部，带封面 + 评分 + 进度，HiDPI 画质）
- 追番总结卡（含统计概览 + TOP 番剧）

### 界面特性
- 6 种预设主题色切换（靛蓝/jade/紫/珊瑚/琥珀/玫瑰）
- 深色/浅色模式
- 响应式设计，移动端适配
- 移动端底部导航栏 + 左右滑动切换 Tab
- 键盘快捷键（1-4 切换 Tab，/ 聚焦搜索，Esc 关闭弹窗）
- 封面图 IntersectionObserver 懒加载
- 骨架屏（表格/画廊/详情三种模式）
- 焦点环（按钮/弹窗键盘无障碍导航）
- PWA 支持（可安装到桌面，缓存静态资源；私人 API 数据仅在线访问）
- 中英文国际化（i18n）
- Phosphor Icons 图标体系

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.0 | Web 框架 |
| Spring Data JPA | - | ORM |
| Spring Security | - | 表单登录、API 认证、CSRF 写入保护 |
| Spring Cache + Caffeine | - | Bangumi API 响应缓存 |
| Flyway | 9.22.3 | 数据库迁移（V0–V9，含 V6.1、V8 Java 迁移） |
| Flyway MySQL | 9.22.3 | MySQL 方言支持 |
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
| 原生 JavaScript（6 个脚本） | 单页应用、逐集记录、日常追番与统一进退集交互 |
| Chart.js | 统计图表（7 个实例） |
| SortableJS | 拖拽排序 |
| Marked + DOMPurify | Markdown 渲染 + XSS 防护 |
| Phosphor Icons | 图标（CDN） |
| Outfit 字体 | Google Fonts |
| CSS 自定义属性（~940 行） | 主题色/深色模式/进度条/组件样式 |

### 测试

| 技术 | 用途 |
|------|------|
| JUnit 5 | 测试框架 |
| Mockito | Mock 框架 |
| Spring Security Test | 认证测试 |
| H2 | 测试内存数据库 |
| Node.js 内置测试运行器 | 请求令牌、恢复确认、逐集补录与 Service Worker 缓存行为测试 |

## 项目结构

```
OtakuLog/
├── src/main/java/com/otakulog/
│   ├── OtakuLogApplication.java              # 应用入口
│   ├── common/
│   │   ├── ApiResponse.java                  # 统一 API 响应体
│   │   ├── GlobalExceptionHandler.java       # 全局异常处理（11 种异常映射）
│   │   ├── ResourceNotFoundException.java    # 资源不存在异常（→404）
│   │   └── ExternalApiException.java         # 外部 API 异常（→502）
│   ├── config/
│   │   ├── SecurityConfig.java               # 表单登录、API 认证与 CSRF
│   │   ├── CorsConfig.java                   # CORS 跨域配置
│   │   ├── CacheConfig.java                  # Caffeine 缓存配置
│   │   └── OpenApiConfig.java                # Swagger/OpenAPI 配置
│   ├── controller/
│   │   ├── AnimeController.java              # 番剧 CRUD + 统计 + 搜索（~310 行）
│   │   ├── BangumiApiController.java         # Bangumi 搜索/详情/导入
│   │   ├── EpisodeHistoryController.java     # 逐集查看与日期核对
│   │   ├── DailyWatchController.java         # 只读日常追番入口
│   │   ├── GroupController.java              # 番剧分组管理
│   │   ├── LoginController.java              # 登录页
│   │   ├── ReportController.java             # 年度报告
│   │   └── SyncApiController.java            # WebDAV 数据同步
│   ├── dto/                                  # 番剧、标签、分组与报告传输对象
│   ├── entity/
│   │   ├── Anime.java                        # 番剧实体（17 字段 + 2 审计）
│   │   ├── AnimeGroup.java                   # 分组实体
│   │   ├── EpisodeRecord.java                # 每集观看记录（热力图数据源）
│   │   ├── Tag.java                          # 标签实体
│   │   └── BaseEntity.java                   # 基础实体（createdAt/updatedAt）
│   ├── enums/
│   │   └── AnimeStatus.java                  # WATCHING/FINISHED/PLANNING/DROPPED
│   ├── repository/
│   │   ├── AnimeRepository.java              # 番剧数据访问（~30 查询方法）
│   │   ├── AnimeGroupRepository.java         # 分组数据访问
│   │   ├── TagRepository.java                # 标签数据访问
│   │   └── EpisodeRecordRepository.java      # 观看记录数据访问
│   ├── service/
│   │   ├── BackupService.java                # 完整备份、预览与原子恢复
│   │   ├── WatchProgressService.java         # 统一观看写入规则
│   │   ├── EpisodeHistoryService.java        # 记录分页、快照校验与事务锁
│   │   ├── DailyWatchService.java            # 可继续作品与本地放送参考
│   │   ├── AnimeService.java                 # 番剧服务接口
│   │   ├── BangumiService.java               # Bangumi 服务接口
│   │   ├── AiringScheduleService.java        # 放送时间表接口
│   │   ├── AnnualReportService.java          # 年度报告接口
│   │   ├── TraceMoeService.java              # 以图搜番接口
│   │   ├── WebDavSyncService.java            # WebDAV 同步接口
│   │   └── impl/
│   │       ├── AnimeServiceImpl.java         # 番剧服务实现
│   │       ├── BangumiServiceImpl.java       # Bangumi 服务实现（~360 行）
│   │       ├── AiringScheduleServiceImpl.java # 放送时间表实现
│   │       ├── AnnualReportServiceImpl.java  # 年度报告实现
│   │       ├── TraceMoeServiceImpl.java      # 以图搜番实现
│   │       └── WebDavSyncServiceImpl.java    # WebDAV 同步实现
│   └── util/
│       ├── AnimeVOMapper.java                # 列表与日常入口共用作品映射
│       └── SortUtil.java                     # 排序工具
├── src/main/resources/
│   ├── templates/
│   │   ├── anime.html                        # 主页面模板（~300 行）
│   │   └── login.html                        # 登录页
│   ├── static/
│   │   ├── css/
│   │   │   └── anime.css                     # 样式表（~940 行，CSS 变量体系）
│   │   ├── js/
│   │   │   ├── anime-app.js                  # 前端主逻辑与 CSRF 请求适配
│   │   │   ├── episode-history.js            # 逐集记录查看、补录与冲突提示
│   │   │   ├── daily-watch.js                # 日常追番展示、重试与过期响应保护
│   │   │   ├── watch-actions.js              # 进退集请求去重与结果联动
│   │   │   ├── i18n.js                       # 中英文国际化（~320 行）
│   │   │   └── share-card.js                 # Canvas 分享卡生成（~260 行）
│   │   ├── icons/                            # PWA 图标（192/512）
│   │   ├── manifest.json                     # PWA 清单
│   │   └── sw.js                             # Service Worker
│   ├── db/migration/
│   │   ├── V0__initial_schema.sql            # 空库兼容初始化
│   │   ├── V1__baseline.sql                  # 初始表结构（anime 表）
│   │   ├── V2__add_indexes_and_audit.sql     # 索引 + 审计字段
│   │   ├── V3__add_watch_tracking.sql        # 观看追踪字段
│   │   ├── V4__add_anime_group.sql           # 番剧分组表
│   │   ├── V5__add_episode_record.sql        # 每集观看记录表
│   │   ├── V6__add_tag_system.sql            # 标签表与关联表
│   │   ├── V7__add_watch_season.sql          # 观看季度字段
│   │   └── V9__add_episode_record_source.sql # 观看来源与未知日期
│   └── application.properties                # 应用配置
├── src/test/java/com/otakulog/
│   ├── config/
│   │   └── SecurityConfigTest.java           # 认证、CSRF 与登录退出测试
│   ├── controller/
│   │   ├── AnimeControllerTest.java          # Controller 层测试（13 用例）
│   │   └── EpisodeHistoryTest.java           # 逐集记录接口、权限与回滚
│   ├── deployment/
│   │   └── MySqlDeploymentTest.java          # 显式启用的真实 MySQL 验收
│   ├── repository/
│   │   └── AnimeRepositoryTest.java          # Repository 层测试（8 用例）
│   └── service/
│       ├── AnimeServiceImplTest.java         # 原有 Service 层测试（23 用例）
│       ├── WatchConsistencyTest.java         # 无测试外层事务的观看一致性验收
│       ├── BackupServiceTest.java            # 完整恢复、预览、冲突与回滚
│       ├── AnnualReportServiceTest.java      # 跨年、来源、评分与数据覆盖
│       └── WebDavBackupTest.java             # 模拟远程文件与摘要确认
├── src/test/js/                               # 前端令牌、私人缓存与逐集记录测试
├── pom.xml
└── README.md
```

## 数据库表

| 表 | 用途 |
|----|------|
| `anime` | 核心番剧表，含 Bangumi 关联、历史标记与观看季度 |
| `anime_group` | V4 新增，番剧分组 |
| `anime_group_relation` | V4 新增，分组-番剧多对多关联 |
| `episode_record` | V5 新增，每集观看记录，驱动热力图 |
| `tag` / `anime_tag` | V6 新增，标签与番剧关联 |

V6.1 Java 迁移负责迁移旧字符串标签；V8 Java 迁移负责对齐状态列类型，两者位于 `src/main/java/com/otakulog/`。V0 补齐空库初始化，历史迁移文件保持不变。

### 观看与导入规则

观看进度、状态和逐集记录在同一事务内写入；编辑弹窗的资料与状态也一次提交，失败时全部回滚。单条与批量状态切换使用相同规则。

- 计划从 0 集开始，追中默认记录第 1 集；进到最后一集自动完成，退至 0 集恢复计划。
- 手动或批量标记完成会补齐进度，保留已有完成日期与逐集日期；缺失逐集日期保留未知，不把整部算到今天。
- 有进度时直接改计划会拒绝，先退回 0 集后再切换；改追中或搁置会清除完成日期并保留观看历史。
- 编辑时增加已完成作品的总集数，会恢复追中；总集数不能低于已有进度。主动填写的日期必须为 `yyyy-MM-dd`。
- 旧 JSON 导入仍按名称合并，整次操作原子化。已有进度不能倒退，已有逐集日期保留；JSON/Bangumi 缺少逐集日期时补未知日期记录。

V9 允许 `episode_record.watched_date` 为空，并新增 `record_source`：`WATCHED` 为本次观看，`MANUAL` 为手动补录，`IMPORT` 为导入，`LEGACY` 为来源不可考的已有记录。迁移不修改旧日期，也不把旧估算自动升级为真实观看。热力图只聚合已知日期；有逐集记录时不再回退伪造日期，无记录的历史库仍保留估算兼容。

JSON 与 WebDAV 使用相同的版本化完整备份，包含作品、逐集日期与来源、标签、分组、关联及审计时间。导入先预览，确认后合并恢复；身份、分组或已知日期冲突会拒绝，写入异常整批回滚。已有资料和更高进度保留，不删除本地额外数据；兼容旧版数组。WebDAV 确认时检查文件摘要，远程内容变化则重新预览。操作与 API 见[备份与恢复](docs/备份与恢复.md)，观看规则见[观看写入一致性](docs/superpowers/plans/2026-10-08-观看写入一致性.md)。

### 年度统计规则

年报完成数按完成日期统计；观看集数按逐集日期统计，包含尚未完成作品，不把完成作品的整部集数归入完成年。LEGACY 来源可能含旧估算，单列历史参考，不并入明确日期观看量；日期未知记录、进度缺记录和完成日期未知作品显示为全库覆盖提示，不猜年份。年/月均分只以有效评分作品为分母，无评分显示“未评分”；时长按每集 24 分钟估算，页面明确标记。详见[年度统计口径](docs/年度统计口径.md)。

点击番剧名称可查看逐集日期和来源，在详情内核对或补录实际日期，未知留空。保存只修改该集，保持作品进度、状态和观看起止日期；过期快照返回冲突，不覆盖较新记录。详见[逐集观看记录](docs/逐集观看记录.md)。

## 快速开始

首页日常入口不受下方筛选影响，按最近明确观看日期排列；放送参考只读取保存的放送日，未核实本周更新。进退集显示具体结果并同步详情，有日期草稿时保留输入供核对。规则与 API 见[日常追番入口](docs/日常追番入口.md)。

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

   本地默认连接 `localhost:3306/otaku_log`。

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
node --test src/test/js/*.test.cjs
```

默认 Java 测试使用 H2 内存数据库，无需 MySQL。包括 44 个原有业务测试、29 个安全用例、27 个观看一致性用例、20 个完整恢复用例、4 个模拟 WebDAV HTTP 用例、13 个年度统计用例、20 个逐集记录用例、9 个日常入口用例与 3 个演示样例用例；另有 9 个需要显式启用的 MySQL 用例。全部启用共 178 个 Java 用例。Node 有 57 个前端行为、11 个演示工具及 1 个需显式启用的真实演示冒烟，共 69 个用例；需要 Node.js 22 或更高版本，无需 npm 安装依赖。语法检查覆盖 6 个业务脚本、sw.js 与 3 个演示脚本。

H2 测试禁用 Flyway，因此不能代替 MySQL 数据库迁移和 Docker 部署验证。

### 自动检查与隔离演示

推送和 PR 自动触发 `.github/workflows/ci.yml`，执行 Java 17、Node 22 和真实 MySQL 8 回归并保存报告。构建 JAR 后运行 `node scripts/demo.cjs`，在 `http://127.0.0.1:18080` 使用打印的临时凭据测试虚构作品；每次随机创建独立库，正常退出时清理，不使用自用数据。

真实演示冒烟需先打包，并设置 `OTAKULOG_DEMO_TEST=true` 后运行 Node 测试。详细启动、CI 与强制退出清理边界见[持续集成与演示](docs/持续集成与演示.md)。

### 认证与私人数据

- 登录页和必要静态资源允许匿名访问；业务 API 和 API 文档需要登录。
- 未登录或会话失效的 API 请求返回 JSON 和 HTTP `401`；普通页面跳转登录页。
- `POST`、`PUT`、`PATCH`、`DELETE` 等写操作需要当前会话的 CSRF 令牌。主页面通过 `_csrf`、`_csrf_header` 元标签提供令牌和头名称，统一请求函数自动携带；登录和退出表单由 Thymeleaf 自动加入隐藏令牌。
- 已登录但令牌缺失或无效时返回 JSON 和 HTTP `403`，刷新页面后重试。会话失效时前端引导重新登录。
- 私人 API 响应不保存在 Service Worker 缓存；当前 v10 激活时清理旧版 OtakuLog 缓存。离线 API 返回 HTTP `503`，不回退到旧私人数据。
- 当前 PWA 提供静态资源缓存和安装能力，未提供完整离线启动或离线编辑。

### API 文档

登录后访问 Swagger UI：http://localhost:8080/swagger-ui.html。写接口需携带当前会话 CSRF 令牌。

### Docker 部署

需要 Docker 引擎和 Docker Compose v2 或更新版本。容器应用连接 `mysql:3306/otakulog`；MySQL 通过 SQL 健康检查后应用才启动。数据库不映射宿主端口，因此可以和本地 MySQL 共存。

```bash
# 首次启动前设置凭据；也可在 PowerShell 使用 $env:变量名='值'
export DB_PASS=your_password
export ADMIN_USER=admin
export ADMIN_PASS=your_admin_password

# 构建并启动
docker compose up -d --build

# 查看日志
docker compose ps
docker compose logs -f otakulog

# 停止
docker compose down
```

启动后访问 http://localhost:8080，使用设置的管理员凭据。未设置时仍为 `admin` / `admin`。若本地预览占用 8080，启动前设置 `APP_PORT=18080`，再访问对应端口。

数据写入 Compose 项目对应的 `mysql_data` 卷。重启或 `docker compose down` 保留该卷；不要用 `down -v` 停止日常服务，它会删除数据库。升级前备份数据库，再运行 `docker compose up -d --build`，Flyway 自动迁移，JPA 仅校验结构。

`DB_PASS` 同时用于首次初始化 MySQL 和应用连接。已有卷不会随环境变量变化自动修改数据库密码；修改时需先在 MySQL 内变更密码并同步配置。管理员凭据由应用每次启动读取。

| 变量 | 默认值 | 用途 |
|---|---|---|
| `DB_PASS` | `123456` | 容器数据库 root 密码与应用连接密码 |
| `ADMIN_USER` / `ADMIN_PASS` | `admin` / `admin` | 应用登录凭据 |
| `APP_PORT` | `8080` | 应用宿主端口 |
| `SPRING_DATASOURCE_URL` | 本地 `localhost:3306/otaku_log` | 本地启动时覆盖 JDBC；Compose 固定为内部服务地址 |

2026-10-08：Compose 配置可解析；真实 MySQL 的空库启动、重启保留数据、V5 升级和 V1 基线升级已加入隔离测试。当前验证环境的 Docker Desktop 安装注册信息缺失，无法连接引擎，尚未验证容器构建、健康检查运行及容器重启。详见[部署验收记录](docs/superpowers/plans/2026-10-08-可复现部署.md)。

### MySQL 部署回归测试

测试默认跳过；显式启用后需要能创建和删除数据库的测试账号。每次随机创建 `otakulog_verify_` 前缀的隔离库，结束后仅删除自己创建的库，不连接业务库。

```powershell
$env:OTAKULOG_MYSQL_TEST='true'
$env:OTAKULOG_MYSQL_SERVER='127.0.0.1:3306'
$env:DB_USER='root'
$env:DB_PASS='测试数据库密码'
mvn -q '-Dtest=MySqlDeploymentTest' test
```

迁移保留历史文件：V0 为新库提供 V2 之前的初始结构，避免 V1 快照与 V2/V3 重复建列；已有 V1 基线库忽略 V0。V8 将旧 VARCHAR 状态列对齐为 Hibernate 要求的 ENUM，已有相同 ENUM 不改表；遇到未知状态会拒绝迁移并保留原值，应先备份并修正数据再处理失败的 Flyway 记录。

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

## 许可证

MIT License
