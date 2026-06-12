# 核心能力测试护栏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Bangumi 补链、推荐、热力图和对应控制器接口补齐稳定的自动化测试护栏，降低后续前端拆分和服务层整理时的回归风险。

**Architecture:** 基于现有测试结构做增量补强，不新增测试框架。`AnimeServiceImplTest` 继续承担服务层集成测试，通过 `@MockBean BangumiService` 与真实仓储配合验证行为；`AnimeControllerTest` 继续承担控制器响应测试，通过 `MockMvc + @MockBean AnimeService` 验证返回码与响应体结构。

**Tech Stack:** Spring Boot 3.2、Java 17、JUnit 5、MockMvc、H2、Mockito

---

## 文件结构与职责

- `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`
  - 补充服务层核心行为测试
  - 增加测试夹具与辅助方法
  - 注入 `EpisodeRecordRepository`
  - Mock `BangumiService`
- `src/test/java/com/otakulog/controller/AnimeControllerTest.java`
  - 补充控制器接口成功/失败场景测试
  - 继续使用 `MockMvc` 校验 `ApiResponse`
- `src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java`
  - 预期不修改
  - 只有在新增测试暴露真实缺陷时才做最小修复
- `src/main/java/com/otakulog/controller/AnimeController.java`
  - 预期不修改
  - 只有在新增测试暴露真实缺陷时才做最小修复

### Task 1: 补强 Service 测试夹具

**Files:**
- Modify: `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`

- [ ] **Step 1: 写出会失败的测试夹具改动**

把 `AnimeServiceImplTest.java` 顶部依赖与字段补成下面这样：

```java
package com.otakulog.service;

import com.otakulog.common.ResourceNotFoundException;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BangumiResult;
import com.otakulog.entity.EpisodeRecord;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.repository.AnimeRepository;
import com.otakulog.repository.EpisodeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnimeServiceImplTest {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private AnimeRepository animeRepository;

    @Autowired
    private EpisodeRecordRepository episodeRecordRepository;

    @MockBean
    private BangumiService bangumiService;
```

并把 `setUp()` 改成：

```java
    @BeforeEach
    void setUp() {
        episodeRecordRepository.deleteAll();
        animeRepository.deleteAll();
    }
```

在类中新增两个辅助方法：

```java
    private AnimeDTO createDetailedDTO(
            String name,
            int total,
            String season,
            double score,
            String tags,
            Integer bangumiId,
            String coverUrl,
            boolean legacy,
            String watchStartDate) {
        AnimeDTO dto = new AnimeDTO();
        dto.setName(name);
        dto.setTotalEpisodes(total);
        dto.setSeason(season);
        dto.setScore(score);
        dto.setRemark("test");
        dto.setTags(tags);
        dto.setBangumiId(bangumiId);
        dto.setCoverUrl(coverUrl);
        dto.setLegacy(legacy);
        dto.setWatchStartDate(watchStartDate);
        return dto;
    }

    private BangumiResult createBangumiResult(int id, String name, String nameCn, String image, double score) {
        BangumiResult result = new BangumiResult();
        result.setId(id);
        result.setName(name);
        result.setNameCn(nameCn);
        result.setImage(image);
        result.setScore(score);
        return result;
    }
```

- [ ] **Step 2: 运行单个测试类，确认当前夹具能编译**

Run: `mvn -q -Dtest=AnimeServiceImplTest test`

Expected: 测试类能够启动；如果后续测试尚未加入，当前已有用例继续通过

- [ ] **Step 3: 如编译失败，仅做最小修复**

如果出现以下情况，按最小方式修复：

```java
// 如果 IDE 或编译器提示未使用 import，可删除未使用 import
// 不要在这一步改业务代码
```

- [ ] **Step 4: 再次运行单个测试类确认恢复通过**

Run: `mvn -q -Dtest=AnimeServiceImplTest test`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/com/otakulog/service/AnimeServiceImplTest.java
git commit -m "test(service): 补强核心能力测试夹具"
```

### Task 2: 为 Bangumi 补链与推荐补服务层测试

**Files:**
- Modify: `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`

- [ ] **Step 1: 写 `matchBangumi` 的失败测试**

在 `AnimeServiceImplTest.java` 中新增以下用例：

```java
    @Test
    void 当番剧已有BangumiId时应该直接返回当前数据() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("已有补链", 12, "2024冬", 8.0, "校园", 1234, null, false, "2024-01-01"));

        AnimeVO matched = animeService.matchBangumi(saved.getId());

        assertEquals(1234, matched.getBangumiId());
    }

    @Test
    void 当搜索结果存在精确匹配时应该写入BangumiId并补齐封面() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("孤独摇滚", 12, "2022秋", 9.0, "音乐", null, null, false, "2022-10-01"));

        when(bangumiService.search(eq("孤独摇滚"), eq(5))).thenReturn(List.of(
                createBangumiResult(999, "孤独摇滚", "孤独摇滚", "https://img.test/bocchi.jpg", 8.8)
        ));

        AnimeVO matched = animeService.matchBangumi(saved.getId());

        assertEquals(999, matched.getBangumiId());
        assertEquals("https://img.test/bocchi.jpg", matched.getCoverUrl());
    }

    @Test
    void 当搜索结果没有精确匹配时应该抛出异常() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("未匹配番剧", 12, "2024春", 7.0, "悬疑", null, null, false, "2024-04-01"));

        when(bangumiService.search(eq("未匹配番剧"), eq(5))).thenReturn(List.of(
                createBangumiResult(1001, "别的作品", "别的作品", null, 7.2)
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> animeService.matchBangumi(saved.getId()));

        assertEquals("未在 Bangumi 找到匹配结果", ex.getMessage());
    }
```

- [ ] **Step 2: 运行 `matchBangumi` 相关用例，确认当前行为**

Run: `mvn -q -Dtest=AnimeServiceImplTest#当番剧已有BangumiId时应该直接返回当前数据,AnimeServiceImplTest#当搜索结果存在精确匹配时应该写入BangumiId并补齐封面,AnimeServiceImplTest#当搜索结果没有精确匹配时应该抛出异常 test`

Expected: PASS；如果失败，说明真实行为与预期不一致，需要下一步最小修复

- [ ] **Step 3: 写 `batchMatchBangumi` 与 `getRecommendations` 的失败测试**

继续新增以下用例：

```java
    @Test
    void 当批量补链部分成功部分失败时应该返回准确统计() {
        animeService.addAnime(createDetailedDTO("命运石之门", 24, "2011春", 9.5, "科幻", null, null, false, "2024-01-01"));
        animeService.addAnime(createDetailedDTO("匹配失败作品", 12, "2024冬", 6.5, "悬疑", null, null, false, "2024-02-01"));
        animeService.addAnime(createDetailedDTO("已有链接作品", 12, "2024冬", 8.0, "日常", 4567, null, false, "2024-03-01"));

        when(bangumiService.search(eq("命运石之门"), eq(5))).thenReturn(List.of(
                createBangumiResult(2001, "命运石之门", "命运石之门", null, 9.1)
        ));
        when(bangumiService.search(eq("匹配失败作品"), eq(5))).thenReturn(List.of());

        Map<String, Object> result = animeService.batchMatchBangumi();

        assertEquals(1, result.get("matched"));
        assertEquals(1, result.get("failed"));
        assertEquals(2, result.get("total"));
    }

    @Test
    void 当生成推荐时应该过滤已追番和重复BangumiId() {
        animeService.addAnime(createDetailedDTO("葬送的芙莉莲", 28, "2023秋", 9.5, "奇幻,冒险", 3001, null, false, "2024-01-01"));
        animeService.addAnime(createDetailedDTO("迷宫饭", 24, "2024冬", 8.8, "奇幻,美食", null, null, false, "2024-02-01"));

        when(bangumiService.searchByTag(eq("奇幻"), anyInt())).thenReturn(List.of(
                createBangumiResult(3001, "葬送的芙莉莲", "葬送的芙莉莲", null, 9.5),
                createBangumiResult(4001, "奇幻新作", "奇幻新作", "https://img.test/fantasy.jpg", 8.0)
        ));
        when(bangumiService.searchByTag(eq("冒险"), anyInt())).thenReturn(List.of(
                createBangumiResult(4001, "奇幻新作", "奇幻新作", "https://img.test/fantasy.jpg", 8.0),
                createBangumiResult(4002, "冒险物语", "冒险物语", "https://img.test/adventure.jpg", 7.8)
        ));
        when(bangumiService.searchByTag(eq("美食"), anyInt())).thenReturn(List.of(
                createBangumiResult(4003, "美食之旅", "美食之旅", "https://img.test/food.jpg", 7.5)
        ));

        List<Map<String, Object>> recommendations = animeService.getRecommendations();

        assertFalse(recommendations.isEmpty());
        assertTrue(recommendations.stream().noneMatch(item -> Integer.valueOf(3001).equals(item.get("id"))));
        assertEquals(recommendations.size(),
                recommendations.stream().map(item -> item.get("id").toString()).distinct().count());
        assertTrue(recommendations.stream().allMatch(item -> item.containsKey("reason")));
    }
```

- [ ] **Step 4: 运行新增服务测试**

Run: `mvn -q -Dtest=AnimeServiceImplTest#当批量补链部分成功部分失败时应该返回准确统计,AnimeServiceImplTest#当生成推荐时应该过滤已追番和重复BangumiId test`

Expected: PASS

- [ ] **Step 5: 如失败，只修最小真实问题**

如果服务实现与预期不符，只允许做最小修复。例如：

```java
// 仅在测试暴露出真实缺陷时修改生产代码
// 不重构结构，不顺手清理无关逻辑
```

- [ ] **Step 6: 提交**

```bash
git add src/test/java/com/otakulog/service/AnimeServiceImplTest.java src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java
git commit -m "test(service): 补齐Bangumi补链与推荐测试"
```

### Task 3: 为热力图补服务层测试

**Files:**
- Modify: `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`

- [ ] **Step 1: 写真实记录优先的失败测试**

新增以下用例：

```java
    @Test
    void 当存在观看记录时热力图应该优先使用真实记录() {
        AnimeVO saved = animeService.addAnime(
                createDetailedDTO("热力图样本", 12, "2024冬", 8.0, "日常", null, null, false, "2024-01-01"));

        EpisodeRecord record = new EpisodeRecord();
        record.setAnimeId(saved.getId());
        record.setEpisodeNumber(1);
        record.setWatchedDate(LocalDate.now());
        episodeRecordRepository.save(record);

        Map<String, Integer> heatmap = animeService.getHeatmap();

        assertTrue(heatmap.containsKey(LocalDate.now().toString()));
        assertEquals(1, heatmap.get(LocalDate.now().toString()));
    }
```

- [ ] **Step 2: 写旧逻辑回退的失败测试**

继续新增以下用例：

```java
    @Test
    void 当没有观看记录时热力图应该回退到旧估算逻辑() {
        animeService.addAnime(createDetailedDTO(
                "旧逻辑热力图", 12, "2024冬", 8.2, "校园", null, null, false, LocalDate.now().minusDays(2).toString()));

        Map<String, Integer> heatmap = animeService.getHeatmap();

        assertTrue(heatmap.containsKey(LocalDate.now().toString()));
        assertTrue(heatmap.get(LocalDate.now().toString()) >= 1);
    }
```

- [ ] **Step 3: 运行热力图测试**

Run: `mvn -q -Dtest=AnimeServiceImplTest#当存在观看记录时热力图应该优先使用真实记录,AnimeServiceImplTest#当没有观看记录时热力图应该回退到旧估算逻辑 test`

Expected: PASS

- [ ] **Step 4: 如失败，做最小修复**

如果需要修复，只允许修补真实缺陷，例如：

```java
// 修复真实记录优先级或回退判断
// 不在这里顺带重写整段热力图逻辑
```

- [ ] **Step 5: 提交**

```bash
git add src/test/java/com/otakulog/service/AnimeServiceImplTest.java src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java
git commit -m "test(service): 补齐热力图测试护栏"
```

### Task 4: 为核心接口补控制器测试

**Files:**
- Modify: `src/test/java/com/otakulog/controller/AnimeControllerTest.java`

- [ ] **Step 1: 写成功与失败场景测试**

在 `AnimeControllerTest.java` 中新增以下用例：

```java
    @Test
    void matchBangumi_shouldReturnUpdatedAnime() throws Exception {
        AnimeVO vo = new AnimeVO();
        vo.setId(1L);
        vo.setBangumiId(1001);
        when(animeService.matchBangumi(1L)).thenReturn(vo);

        mvc.perform(post("/api/anime/1/match-bangumi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bangumiId").value(1001));
    }

    @Test
    void matchBangumi_whenServiceThrows_shouldReturn400() throws Exception {
        when(animeService.matchBangumi(1L)).thenThrow(new IllegalArgumentException("未在 Bangumi 找到匹配结果"));

        mvc.perform(post("/api/anime/1/match-bangumi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未在 Bangumi 找到匹配结果"));
    }

    @Test
    void batchMatchBangumi_shouldReturnSummary() throws Exception {
        when(animeService.batchMatchBangumi()).thenReturn(java.util.Map.of(
                "matched", 2,
                "failed", 1,
                "total", 3
        ));

        mvc.perform(post("/api/anime/batch-match-bangumi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.matched").value(2))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void getRecommendations_shouldReturnList() throws Exception {
        when(animeService.getRecommendations()).thenReturn(java.util.List.of(
                java.util.Map.of("id", 1001, "name", "推荐番剧", "reason", "标签: 奇幻")
        ));

        mvc.perform(get("/api/anime/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1001));
    }

    @Test
    void getHeatmap_shouldReturnDateMap() throws Exception {
        when(animeService.getHeatmap()).thenReturn(java.util.Map.of("2026-06-12", 2));

        mvc.perform(get("/api/anime/heatmap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data['2026-06-12']").value(2));
    }
```

- [ ] **Step 2: 运行控制器测试**

Run: `mvn -q -Dtest=AnimeControllerTest test`

Expected: PASS

- [ ] **Step 3: 如失败，只修控制器真实问题**

允许的最小修复示例：

```java
// 仅修正返回码、message 或响应包装问题
// 不扩展新接口，也不改业务语义
```

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/otakulog/controller/AnimeControllerTest.java src/main/java/com/otakulog/controller/AnimeController.java
git commit -m "test(controller): 补齐核心接口响应测试"
```

### Task 5: 全量验证与收尾

**Files:**
- Modify: `src/test/java/com/otakulog/service/AnimeServiceImplTest.java`
- Modify: `src/test/java/com/otakulog/controller/AnimeControllerTest.java`
- Modify: `src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java`
- Modify: `src/main/java/com/otakulog/controller/AnimeController.java`

- [ ] **Step 1: 运行 Java 编译检查**

Run: `mvn -q -DskipTests compile`

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行完整测试**

Run: `mvn test -q`

Expected: 所有测试通过

- [ ] **Step 3: 运行空白字符检查**

Run: `git diff --check`

Expected: 无输出

- [ ] **Step 4: 最终提交**

```bash
git add src/test/java/com/otakulog/service/AnimeServiceImplTest.java src/test/java/com/otakulog/controller/AnimeControllerTest.java src/main/java/com/otakulog/service/impl/AnimeServiceImpl.java src/main/java/com/otakulog/controller/AnimeController.java
git commit -m "test(core): 为Bangumi推荐与热力图补齐测试护栏"
```

## 计划自检

### 规格覆盖

- `matchBangumi`：已覆盖已有链接、精确匹配成功、无匹配失败
- `batchMatchBangumi`：已覆盖统计与部分失败不中断
- `getRecommendations`：已覆盖标签推荐、去重、原因字段
- `getHeatmap`：已覆盖真实记录优先与旧逻辑回退
- Controller 接口：已覆盖成功与失败响应

### 占位符扫描

本计划未使用 `TODO`、`TBD`、`后续补充` 一类占位语句。所有任务都给出了明确文件、代码片段、运行命令和预期结果。

### 类型一致性

- `BangumiResult` 字段使用 `id/name/nameCn/image/score`，与当前 DTO 一致
- `EpisodeRecord` 使用 `animeId/episodeNumber/watchedDate`，与当前实体一致
- 控制器接口路径与当前 `AnimeController` 已有映射一致
