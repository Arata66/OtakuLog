# CLAUDE.md — OtakuLog 项目约定

> 项目根目录规则手册。面向在此项目中工作的 AI Agent（Claude Code / Codex / 等）。

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
└── db/migration/    # Flyway 迁移（V1-V5）
```

## 关键约束

- **JPA DDL 策略**：`validate`（禁止自动建表，迁移全靠 Flyway）
- **Security**：表单登录 + 静态资源白名单 `/css/**`, `/js/**`, `/manifest.json`, `/sw.js`
- **CORS**：允许 `http://localhost:5173`（Vite 开发服务器）
- **前端**：单页应用（anime.html），原生 JS + Chart.js，无构建工具
- **热力图**：V5 起使用 `episode_record` 表事件驱动聚合，优先查表，fallback 旧估算逻辑

## 数据库表

| 表 | 用途 |
|----|------|
| `anime` | 核心番剧表 |
| `episode_record` | V5 新增，每集观看记录，热力图数据源 |
| `anime_group` | V4 新增，番剧分组 |
| `flyway_schema_history` | Flyway 迁移历史 |

## 前端优化历程（已完成）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 1-6 | `3c5a04b` | 字体/配色/图标/排版/状态/代码质量（taste-skill 6 步） |
| 衍生 | `cd3c3d6` → `fd87220` | 主题稳定性、图标加载、渲染函数拆分、a11y 焦点、数据区域状态、Chart 实例清理 |
| 6 (DOM缓存) | `643aa7e` | 缓存 40+ 常用 DOM 元素到模块级变量，179→107 处查询 |

> 详细进度见 `~/.claude/projects/E--work-OtakuLog/memory/project_frontend_optimization_plan.md`

## 后续方向

- 动态样式提取（进度条宽度、热力图颜色、分组颜色 — JS 生成的内联 style）
- 按钮体系抽象（`.btn-sm`, `.btn`, `.a-btn` 缺乏统一设计）
- 浏览器自动化截图验证
- `otakulog-web/`（Vue 3 重写）与 main 分支旧版的关系梳理

## 深入文档指针

| 文档 | 内容 |
|------|------|
| `README.md` | 功能清单、技术栈、安装步骤 |
| `CLAUDE.local.md` | 个人本地环境配置（不入 git） |
| `docs/superpowers/` | 历史功能设计文档 |
| `~/.claude/plans/greedy-greeting-river.md` | 热力图事件驱动优化方案 |
| `~/.claude/plans/cheerful-leaping-pearl.md` | emoji-bridge GUI 计划（无关项目） |
| `~/.claude/plans/wiggly-doodling-graham.md` | 阶段六 DOM 缓存优化方案 |
| `otakulog-web/` | Vue 3 前后端分离重写（分支 `feature/frontend-separation`） |
