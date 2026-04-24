# 🎬 OtakuLog - 动漫追番管理系统

一个使用 Spring Boot + MySQL 构建的个人动漫追番应用，帮助你管理正在追看的番剧、记录进度和评分。

## ✨ 核心功能

- 📺 **番剧管理** - 添加、编辑、删除追番记录
- ⏯️ **进度管理** - 记录当前看到第几集，自动计算完成度
- ⭐ **评分系统** - 给喜欢的番剧打分
- 📝 **备注功能** - 记录观看感想和笔记
- 💾 **数据持久化** - 所有数据存储到 MySQL 数据库
- 🎨 **简约UI** - 清爽的现代化界面设计

## 🛠️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.0 | Web框架 |
| Spring Data JPA | - | ORM框架 |
| MySQL | 8.0+ | 关系数据库 |
| Thymeleaf | - | 模板引擎 |
| Maven | - | 项目构建 |
| Java | 17+ | 开发语言 |

## 📦 项目结构

```
OtakuLog/
├── src/main/java/com/otakulog/
│   ├── OtakuLogApplication.java       # 应用入口
│   ├── entity/
│   │   └── Anime.java                 # 番剧实体类
│   ├── repository/
│   │   └── AnimeRepository.java        # 数据访问层
│   └── controller/
│       └── AnimeController.java        # 控制层
├── src/main/resources/
│   ├── templates/
│   │   └── anime.html                 # 前端模板
│   └── application.properties         # 配置文件
├── pom.xml                            # Maven配置
└── README.md                          # 说明文档
```

## 🚀 快速开始

### 前置要求

- Java 17 或更高版本
- Maven 3.6+
- MySQL 8.0+

### 安装步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/YOUR_USERNAME/OtakuLog.git
   cd OtakuLog
   ```

2. **创建数据库**
   ```sql
   CREATE DATABASE otaku_log;
   USE otaku_log;
   ```

3. **配置数据库连接**
   
   编辑 `src/main/resources/application.properties`：
   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/otaku_log?useSSL=false&serverTimezone=UTC
   spring.datasource.username=root
   spring.datasource.password=你的MySQL密码
   ```

4. **编译项目**
   ```bash
   mvn clean compile
   ```

5. **启动应用**
   ```bash
   mvn spring-boot:run
   ```

6. **访问应用**
   
   打开浏览器访问：http://localhost:8080

## 📖 使用说明

### 添加番剧
1. 填写表单中的番剧信息（名称、总集数、季度、评分、备注）
2. 点击 "➕ 添加番剧" 按钮
3. 页面自动刷新显示新番剧

### 管理进度
- **➕ 下一集** - 当前集数加1（自动标记为"追中"）
- **⬅️ 上集** - 当前集数减1
- 当集数达到总集数时自动标记为"已完成"

### 编辑番剧
1. 点击 "✏️ 编辑" 按钮进入编辑模式
2. 修改番剧名称、季度、评分、备注
3. 点击 "💾 保存" 保存更改或 "❌ 取消" 放弃编辑

## 🎨 界面特点

- **简约风格** - 使用清爽的灰白色主题
- **响应式设计** - 支持各种屏幕尺寸
- **实时反馈** - 所有操作即时显示结果
- **状态标签** - 一目了然的番剧状态显示

## 📊 数据库表结构

```sql
CREATE TABLE anime (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL COMMENT '番剧名称',
    current_episode INT COMMENT '当前集数',
    total_episodes INT COMMENT '总集数',
    status VARCHAR(50) COMMENT '追番状态',
    score DOUBLE COMMENT '评分',
    season VARCHAR(50) COMMENT '季度',
    remark VARCHAR(500) COMMENT '备注'
);
```

## 💡 核心业务逻辑

### 集数限制
- 上集按钮：集数 > 1 才能点击
- 下一集按钮：集数 < 总集数才能点击
- 自动状态同步：集数 == 总集数 时自动标记为"已完成"

### 编辑限制
- 只能修改：名称、季度、评分、备注
- 不能直接修改：当前集数、状态（通过上/下集按钮控制）

## 🔧 配置说明

关键配置项（`application.properties`）：

```properties
# 数据库配置
spring.datasource.url=jdbc:mysql://localhost:3306/otaku_log
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA配置
spring.jpa.hibernate.ddl-auto=update        # 自动创建/更新表结构
spring.jpa.show-sql=true                    # 打印SQL语句

# 服务器配置
server.port=8080
```

## 🚧 可能的后续功能

- [ ] 删除番剧功能
- [ ] 搜索和过滤
- [ ] 数据导出（CSV/Excel）
- [ ] 深色模式
- [ ] 统计图表（完成率、评分分布）
- [ ] 多用户支持
- [ ] 小说追踪模块

## 🐛 常见问题

**Q: 无法连接数据库？**

A: 检查以下项目：
- MySQL是否正在运行
- 数据库名称、用户名、密码是否正确
- 防火墙是否允许3306端口访问

**Q: 8080端口被占用？**

A: 修改 `application.properties` 中的 `server.port` 为其他值（如8081）

**Q: 表结构没有自动创建？**

A: 确保 `spring.jpa.hibernate.ddl-auto=update`，然后重启应用

## 📝 示例数据

应用首次运行时，数据库为空。你可以手动添加以下示例番剧测试：

| 番剧名 | 总集数 | 季度 | 评分 | 备注 |
|--------|--------|------|------|------|
| 怪兽8号 | 13 | 2024冬季 | 8.5 | 超级爽 |
| 我独自升级 | 13 | 2024冬季 | 9.0 | 天下第一 |
| 凡人修仙传 | 120 | 2023秋季 | 8.0 | 经典好评 |

## 👨‍💻 开发者信息

- **开发者** - 大二计算机专业学生
- **项目用途** - 项目式学习，练习Spring Boot全栈开发

## 📄 许可证

本项目采用 MIT License，详见 LICENSE 文件

## 🙏 致谢

感谢 Spring Boot 社区和所有开源贡献者提供的优秀工具和框架。

---

**Happy Coding! 🎉**

如有问题或建议，欢迎通过 GitHub Issues 反馈。
