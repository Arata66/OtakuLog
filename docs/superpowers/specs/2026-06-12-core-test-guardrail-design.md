# 核心能力测试护栏设计

## 1. 背景

当前项目的核心能力已经较丰富，包含 Bangumi 补链、推荐、热力图、分组、分享卡、统计分析等功能。  
结合仓库现状与本地工作记忆，当前更高优先级的问题不是“继续堆功能”，而是“为已有核心能力补足稳定性护栏”。

目前已有测试主要集中在以下几类：

- 基础 Service 行为测试：`AnimeServiceImplTest`
- 基础 Controller 接口测试：`AnimeControllerTest`
- 基础 Repository 查询测试：`AnimeRepositoryTest`

这些测试已经覆盖了 CRUD、分页、基础统计等能力，但对于最近几轮新增的重要功能，仍缺少足够的行为验证。这样会带来两个直接风险：

- 后续重构 `anime-app.js` 或服务层时，难以及时发现回归
- Bangumi、推荐、热力图这类链路一旦改动，容易出现“功能还能跑，但结果不对”的隐性问题

因此，本轮优化的目标是先建立“核心能力测试护栏”。

## 2. 目标

本轮只做测试补强，不新增业务功能，不主动调整接口设计。

具体目标：

- 为核心服务方法补充关键行为测试
- 为关键控制器接口补充成功与失败场景测试
- 让后续前端拆分和服务层整理有稳定回归保护
- 将验证流程统一到项目既有质量门禁中

## 3. 非目标

本轮明确不做以下事情：

- 不拆分 `src/main/resources/static/js/anime-app.js`
- 不调整前端 UI、样式和交互文案
- 不新增接口、不扩展新功能
- 不重构现有 Repository 或 Service 实现
- 不处理与本轮目标无关的性能优化

这样做是为了把改动面控制在“低风险、高收益”的范围内。

## 4. 范围

### 4.1 Service 层

围绕 `src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java` 中以下能力补充测试：

- `matchBangumi`
- `batchMatchBangumi`
- `getRecommendations`
- `getHeatmap`

### 4.2 Controller 层

围绕 `src/main/java/com/otakulog/controller/AnimeController.java` 中以下接口补充测试：

- `POST /api/anime/{id}/match-bangumi`
- `POST /api/anime/batch-match-bangumi`
- `GET /api/anime/recommendations`
- `GET /api/anime/heatmap`

## 5. 设计原则

### 5.1 测试行为，不测实现细节

测试重点是用户可感知或调用方可感知的结果，例如：

- 返回结果是否正确
- 失败是否给出预期错误
- 数据是否被正确写入或过滤
- 真实记录与回退逻辑是否按优先级生效

不把测试绑定到过细的内部实现步骤，避免以后重构时无谓破坏测试。

### 5.2 在现有测试结构上增量演进

不新建复杂测试框架，优先沿用现有结构：

- `AnimeServiceImplTest` 继续承担服务层集成测试
- `AnimeControllerTest` 继续承担控制器响应测试

这样可以减少引入新模式的成本，也方便与现有测试风格保持一致。

### 5.3 优先覆盖“最值钱的回归点”

本轮不追求测试面最大化，而是优先覆盖：

- 涉及外部数据来源的能力
- 存在分支逻辑和回退逻辑的能力
- 后续重构时最容易被误伤的能力

## 6. 具体设计

### 6.1 `matchBangumi` 测试设计

需要覆盖以下行为：

- 当番剧已经存在 `bangumiId` 时，应该直接返回当前对象，不重复匹配
- 当搜索结果中存在名称精确匹配项时，应该写入 `bangumiId`
- 当本地没有封面而搜索结果有封面时，应该同步封面
- 当没有任何匹配结果时，应该抛出明确异常

测试重点：

- 验证返回值中的 `bangumiId`
- 验证封面补齐行为
- 验证异常消息不是空泛失败

### 6.2 `batchMatchBangumi` 测试设计

需要覆盖以下行为：

- 只处理 `bangumiId` 为空的番剧
- 单个匹配失败不应中断后续匹配
- 返回结果中应正确统计 `matched`、`failed`、`total`

测试重点：

- 混合成功与失败样本时，统计结果要准确
- 已有关联的番剧不应被计入待处理集合

说明：

- 该方法内部存在节流等待逻辑，本轮测试以行为正确为主，不对等待时长做断言

### 6.3 `getRecommendations` 测试设计

需要覆盖以下行为：

- 能从现有追番标签中提取高频标签作为推荐依据
- 已追番作品不能再次出现在推荐结果中
- 已存在的 `bangumiId` 不能再次出现在推荐结果中
- 推荐结果数量受到上限约束

测试重点：

- 验证去重是否覆盖“同名”和“同 Bangumi ID”两种场景
- 验证返回结果中包含推荐原因字段

### 6.4 `getHeatmap` 测试设计

需要覆盖以下行为：

- 当 `episode_record` 中存在观看记录时，应优先使用真实记录聚合
- 当 `episode_record` 没有数据时，应回退到旧估算逻辑
- 返回结果应覆盖过去一年日期，并使用日期字符串作为键

测试重点：

- 验证真实记录优先级高于旧估算逻辑
- 验证回退逻辑在无记录时仍能生成非空热力图数据

### 6.5 Controller 接口测试设计

控制器层补强以响应语义为主，重点验证：

- 成功时返回 `200`
- 失败时返回预期状态码
- `ApiResponse` 中的 `code`、`data`、`message` 结构符合现有约定

具体覆盖：

- `match-bangumi` 成功返回更新后的番剧对象
- `match-bangumi` 失败返回 `400`
- `batch-match-bangumi` 成功返回统计摘要
- `recommendations` 成功返回列表
- `heatmap` 成功返回日期映射

## 7. 文件影响

预计只修改测试文件：

- `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`
- `src/test/java/com/otakulog/controller/AnimeControllerTest.java`

如现有测试结构不足以表达新场景，允许补充测试辅助方法，但不新增与本轮无关的测试基础设施。

## 8. 验证方案

按项目约定执行以下校验：

```bash
mvn -q -DskipTests compile
mvn test -q
git diff --check
```

如果测试新增后暴露出真实缺陷，优先修复缺陷，再让测试稳定通过；不接受通过放宽断言来掩盖问题。

## 9. 实施顺序

建议实施顺序如下：

1. 先补 `AnimeServiceImplTest` 中的核心能力测试
2. 再补 `AnimeControllerTest` 中的接口行为测试
3. 运行编译、测试、空白字符检查
4. 确认测试护栏稳定后，再进入下一阶段的前端拆分

## 10. 后续衔接

本轮完成后，下一阶段将以这套测试护栏为基础，推进：

- `anime-app.js` 按职责拆分
- 前端交互与状态管理整理
- 服务层和接口层的小范围清理

这样可以把“先稳住，再演进”的节奏固定下来，避免每一轮改动都重新承担整包回归风险。
