# AGENTS.md — OtakuLog 项目约定

> 项目根目录规则手册。面向在此项目中工作的 AI Agent（Codex / Codex / 等）。

## 语言约定

- 所有对话、文档、代码注释使用中文
- 提交信息使用中文描述
- 注释用 `//`，不用 JSDoc 块

## Git 工作流

- 提交格式：`feat(scope): 描述` | `fix(scope): 描述` | `docs(scope): 描述` | `refactor(scope): 描述`
- 分支命名：`<用户名>/<功能描述>`
- 每个阶段性更新完成后立即 `git push`
- 推送失败 → 停止后续修改，等待用户确认

## 质量门禁

每次代码修改后运行：
```bash
mvn -q -DskipTests compile    # Java 编译检查
node --check <file>            # JS 语法检查（如有改动）
git diff --check               # 空白字符检查
```

## 项目结构速查

```
src/main/java/com/otakulog/
├── config/          # SecurityConfig, CorsConfig, CacheConfig, OpenApiConfig
├── controller/      # AnimeController, BangumiApiController, GroupController, LoginController, SyncApiController
├── dto/             # 数据传输对象
├── entity/          # JPA 实体（Anime, EpisodeRecord, AnimeGroup 等）
├── enums/           # AnimeStatus 等枚举
├── repository/      # Spring Data JPA 接口
├── service/         # AnimeService, BangumiService, TraceMoeService, WebDavSyncService 等
└── util/            # 工具类
src/main/resources/
├── templates/       # Thymeleaf 模板（anime.html, login.html）
├── static/          # 前端资源（js/, css/, manifest.json, sw.js）
└── db/migration/    # Flyway SQL 迁移（V0-V7、V9）；V6.1、V8 为 Java 迁移
```

## 关键约束

- **JPA DDL 策略**：`validate`（禁止自动建表，迁移全靠 Flyway）
- **Security**：表单登录 + 静态资源白名单 `/css/**`, `/js/**`, `/manifest.json`, `/sw.js`
- **CORS**：允许 `http://localhost:5173`（前端开发跨域）
- **前端**：单页应用（anime.html），原生 JS + Chart.js，无构建工具
- **热力图**：V5 起使用 `episode_record` 表事件驱动聚合，优先查表，fallback 旧估算逻辑
- **日常观看写入**：统一调用 `WatchProgressService`，必须处于服务事务内；未知逐集日期存 NULL，来源单独记录，不能根据进度猜日期；已有记录时热力图不回退估算
- **备份恢复**：统一调用 `BackupService`，本地与 WebDAV 复用同一校验；备份 key 只作文件内引用，恢复事务与真实写入前校验不可绕过
- **年度统计**：完成数按完成日期，观看量按逐集日期且排除 LEGACY；来源不明历史数据和全库缺失提示单列，禁止猜年份；均分仅纳入有效评分，时长必须标记估算

## 数据库表

| 表 | 用途 |
|----|------|
| `anime` | 核心番剧表 |
| `episode_record` | V5 新增，每集观看记录，热力图数据源 |
| `anime_group` | V4 新增，番剧分组 |
| `flyway_schema_history` | Flyway 迁移历史 |

## 工作流程

- 每个阶段任务完成后，自动执行 neat-freak 同步记忆和文档
- 每个阶段任务完成后，向用户给出总结：变更摘要、当前进度、后续可选方向
- 每次执行用户命令后，给出简洁反馈（做了什么、结果如何）
- 每次对话收尾时，自动检查并启动当前开发版本的 Spring Boot 应用，验证页面可访问后给出预览地址；已有同版本健康进程则复用，代码更新后重启自己启动的预览进程，避免重复启动
- 预览优先使用现有本地数据库；端口占用或启动失败时说明实际原因，不终止来源不明的进程，也不自动清空或替换用户数据
- 总结内容写入记忆文件（`project_frontend_optimization_plan.md`），不写入 AGENTS.md

## 深入文档指针

| 文档 | 内容 |
|------|------|
| `README.md` | 功能清单、技术栈、安装步骤 |
| `Codex.local.md` | 个人本地环境配置（不入 git） |
| `docs/superpowers/` | 历史功能设计文档 |
| `docs/superpowers/plans/2026-10-08-可复现部署.md` | 部署方案、隔离测试与容器验证边界 |
| `docs/superpowers/plans/2026-10-08-观看写入一致性.md` | 观看事务、状态规则、日期来源与回滚验收 |
| `docs/备份与恢复.md` | 完整备份格式、合并规则、操作与 API |
| `docs/年度统计口径.md` | 年度与月度规则、历史来源、数据覆盖范围与 API 字段 |
| `docs/逐集观看记录.md` | 逐集日期补录、来源核对、快照冲突与 API |
| `~/.Codex/plans/OtakuLog-可靠自用第一阶段.md` | 当前实施工作区与阶段入口 |
