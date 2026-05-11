# OtakuLog - 追番日记

一个基于 Spring Boot 的个人动漫追番管理系统，帮助你管理追番记录、追踪观看进度、发现新番剧。

## 核心功能

### 番剧管理
- 添加、编辑、删除追番记录
- 进度追踪（上一集/下一集），自动标记完成状态
- 评分与备注
- 表格/画廊双视图模式
- 批量操作（删除、修改状态）
- 拖拽排序
- 番剧分组/收藏夹

### Bangumi 集成
- 搜索 Bangumi 番剧数据库
- 一键导入 Bangumi 追番记录
- 自动匹配 Bangumi 链接（单个/批量）
- 从 Bangumi 同步封面、评分、简介等信息
- 关联 Bangumi 作品详情

### 数据分析
- 观看统计概览（追番数、完成率、平均评分）
- 观看热力图（类似 GitHub 贡献图）
- 月度完成趋势图表
- 季度分布统计
- 观看时间线

### 智能推荐
- 基于标签的相似番剧推荐
- 详情弹窗中展示推荐结果

### 以图搜番
- 基于 trace.moe 的截图搜索
- 上传图片即可找到对应番剧

### 分享功能
- 追番总结卡生成
- 番剧分享卡（带封面和评分）

### 数据同步
- JSON 导入/导出
- WebDAV 多设备同步

### 界面特性
- 6 种预设主题色切换
- 响应式设计，支持移动端
- 移动端左右滑动切换 Tab
- 键盘快捷键（1-4 切换 Tab，/ 搜索）
- 封面图懒加载
- PWA 支持（可安装到桌面）
- 中英文国际化

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.0 | Web 框架 |
| Spring Data JPA | - | ORM 框架 |
| Spring Security | - | 安全框架 |
| Spring Cache + Caffeine | - | 缓存 |
| Flyway | - | 数据库迁移 |
| MySQL | 8.0+ | 关系数据库 |
| Thymeleaf | - | 模板引擎 |
| Swagger/OpenAPI | - | API 文档 |
| Maven | - | 项目构建 |
| Java | 17+ | 开发语言 |

## 项目结构

```
OtakuLog/
├── src/main/java/com/otakulog/
│   ├── OtakuLogApplication.java          # 应用入口
│   ├── config/
│   │   ├── CacheConfig.java              # 缓存配置
│   │   ├── CorsConfig.java               # 跨域配置
│   │   ├── OpenApiConfig.java            # Swagger 配置
│   │   └── SecurityConfig.java           # 安全配置
│   ├── controller/
│   │   ├── AnimeController.java          # 番剧 API
│   │   ├── BangumiApiController.java     # Bangumi API
│   │   ├── GroupController.java          # 分组管理
│   │   ├── LoginController.java          # 登录
│   │   └── SyncApiController.java        # WebDAV 同步
│   ├── dto/                              # 数据传输对象
│   ├── entity/                           # 实体类
│   ├── enums/                            # 枚举
│   ├── repository/                       # 数据访问层
│   ├── service/                          # 业务逻辑层
│   │   ├── AnimeService.java             # 番剧服务
│   │   ├── BangumiService.java           # Bangumi 集成
│   │   ├── AiringScheduleService.java    # 放送时间表
│   │   ├── TraceMoeService.java          # 以图搜番
│   │   └── WebDavSyncService.java        # WebDAV 同步
│   └── util/                             # 工具类
├── src/main/resources/
│   ├── templates/anime.html              # 主页模板
│   ├── static/
│   │   ├── js/anime-app.js               # 前端逻辑
│   │   ├── js/i18n.js                    # 国际化
│   │   ├── js/share-card.js              # 分享卡
│   │   ├── css/anime.css                 # 样式
│   │   ├── manifest.json                 # PWA 配置
│   │   └── sw.js                         # Service Worker
│   └── db/migration/                     # Flyway 迁移脚本
├── pom.xml
└── README.md
```

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
   CREATE DATABASE otaku_log;
   ```

3. **配置数据库连接**

   编辑 `src/main/resources/application.properties`：
   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/otaku_log?useSSL=false&serverTimezone=UTC
   spring.datasource.username=root
   spring.datasource.password=你的MySQL密码
   ```

4. **编译并启动**
   ```bash
   mvn clean compile
   mvn spring-boot:run
   ```

5. **访问应用**

   打开浏览器访问：http://localhost:8080

### API 文档

启动后访问 Swagger UI：http://localhost:8080/swagger-ui.html

## 配置说明

关键配置项（`application.properties`）：

```properties
# 数据库
spring.datasource.url=jdbc:mysql://localhost:3306/otaku_log
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false

# Flyway
spring.flyway.enabled=true

# 服务器
server.port=8080

# Bangumi API（可选）
bangumi.user-agent=OtakuLog/1.0

# WebDAV 同步（可选）
webdav.url=https://your-webdav-server/otakulog/
webdav.username=your-username
webdav.password=your-password
```

## 许可证

本项目采用 MIT License。
