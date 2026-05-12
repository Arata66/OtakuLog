package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BatchRequest;
import com.otakulog.enums.AnimeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.otakulog.service.AiringScheduleService;
import com.otakulog.service.AnimeService;
import com.otakulog.util.SortUtil;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "番剧管理", description = "番剧CRUD及查询接口")
@Controller
public class AnimeController {

    private final AnimeService animeService;
    private final AiringScheduleService airingScheduleService;

    public AnimeController(AnimeService animeService, AiringScheduleService airingScheduleService) {
        this.animeService = animeService;
        this.airingScheduleService = airingScheduleService;
    }

    @GetMapping("/")
    public String index() {
        return "anime";
    }

    @Operation(summary = "添加番剧")
    @PostMapping("/api/anime/add")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> addAnime(@Valid @RequestBody AnimeDTO dto) {
        try {
            AnimeVO vo = animeService.addAnime(dto);
            return ResponseEntity.ok(ApiResponse.success("添加成功", vo));
        } catch (IllegalArgumentException e) {
            if ("duplicate_name".equals(e.getMessage())) {
                return ResponseEntity.status(409).body(ApiResponse.error(409, "已存在同名番剧"));
            }
            if ("duplicate_bangumi".equals(e.getMessage())) {
                return ResponseEntity.status(409).body(ApiResponse.error(409, "该 Bangumi 作品已添加"));
            }
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "下一集")
    @PostMapping("/api/anime/{id}/next-episode")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> nextEpisode(@PathVariable Long id) {
        try {
            AnimeVO vo = animeService.nextEpisode(id);
            return ResponseEntity.ok(ApiResponse.success(vo));
        } catch (IllegalArgumentException e) {
            if ("reached_max".equals(e.getMessage())) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "reached_max"));
            }
            return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage()));
        }
    }

    @Operation(summary = "上一集")
    @PostMapping("/api/anime/{id}/prev-episode")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> prevEpisode(@PathVariable Long id) {
        try {
            AnimeVO vo = animeService.prevEpisode(id);
            return ResponseEntity.ok(ApiResponse.success(vo));
        } catch (IllegalArgumentException e) {
            if ("reached_min".equals(e.getMessage())) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "reached_min"));
            }
            return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage()));
        }
    }

    @Operation(summary = "更新番剧信息")
    @PostMapping("/api/anime/{id}/update")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> updateAnime(
            @PathVariable Long id,
            @Valid @RequestBody AnimeUpdateDTO dto) {
        AnimeVO vo = animeService.updateAnime(id, dto);
        return ResponseEntity.ok(ApiResponse.success("更新成功", vo));
    }

    @Operation(summary = "更新番剧状态")
    @PostMapping("/api/anime/{id}/status")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        try {
            AnimeStatus animeStatus = AnimeStatus.valueOf(status.toUpperCase());
            AnimeVO vo = animeService.updateStatus(id, animeStatus);
            return ResponseEntity.ok(ApiResponse.success(vo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "删除番剧")
    @DeleteMapping("/api/anime/{id}")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> deleteAnime(@PathVariable Long id) {
        animeService.deleteAnime(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @Operation(summary = "批量删除")
    @PostMapping("/api/anime/batch-delete")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> batchDelete(@RequestBody BatchRequest request) {
        animeService.batchDelete(request.getIds());
        return ResponseEntity.ok(ApiResponse.success("批量删除成功", null));
    }

    @Operation(summary = "批量更新状态")
    @PostMapping("/api/anime/batch-status")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> batchUpdateStatus(@RequestBody BatchRequest request) {
        try {
            AnimeStatus animeStatus = AnimeStatus.valueOf(request.getStatus().toUpperCase());
            animeService.batchUpdateStatus(request.getIds(), animeStatus);
            return ResponseEntity.ok(ApiResponse.success("批量状态更新成功", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "搜索番剧")
    @GetMapping("/api/anime/search")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<AnimeVO>>> searchAnime(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "id-desc") String sortBy,
            @RequestParam(required = false) String tag) {

        AnimeStatus animeStatus = parseStatus(status);
        List<AnimeVO> results = animeService.searchAnime(name, animeStatus, sortBy, tag);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @Operation(summary = "分页搜索番剧")
    @GetMapping("/api/anime/page")
    @ResponseBody
    public ResponseEntity<ApiResponse<Page<AnimeVO>>> searchAnimePaged(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false, defaultValue = "id-desc") String sortBy,
            @RequestParam(required = false) String tag) {

        AnimeStatus animeStatus = parseStatus(status);
        Pageable pageable = buildPageable(page, size, sortBy);
        Page<AnimeVO> results = animeService.searchAnimePaged(name, animeStatus, pageable, tag);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @Operation(summary = "获取统计概览")
    @GetMapping("/api/anime/stats")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getStats()));
    }

    @Operation(summary = "获取详细统计")
    @GetMapping("/api/anime/stats/detailed")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDetailedStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getDetailedStats()));
    }

    @Operation(summary = "获取季度统计")
    @GetMapping("/api/anime/stats/seasons")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSeasonStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getSeasonStats()));
    }

    @Operation(summary = "获取增强统计")
    @GetMapping("/api/anime/stats/enhanced")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEnhancedStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getEnhancedStats()));
    }

    @Operation(summary = "获取时间线")
    @GetMapping("/api/anime/timeline")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<AnimeVO>>> getTimeline(
            @RequestParam(defaultValue = "watch") String mode) {
        return ResponseEntity.ok(ApiResponse.success(animeService.getTimeline(mode)));
    }

    @Operation(summary = "获取日历数据")
    @GetMapping("/api/anime/calendar")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<Integer, List<AnimeVO>>>> getCalendar() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getCalendarData()));
    }

    @Operation(summary = "获取放送时间表")
    @GetMapping("/api/anime/airing-schedule")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAiringSchedule() {
        return ResponseEntity.ok(ApiResponse.success(airingScheduleService.getAiringSchedule()));
    }

    @Operation(summary = "匹配Bangumi链接")
    @PostMapping("/api/anime/{id}/match-bangumi")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> matchBangumi(@PathVariable Long id) {
        try {
            AnimeVO vo = animeService.matchBangumi(id);
            return ResponseEntity.ok(ApiResponse.success("匹配成功", vo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "批量匹配Bangumi链接")
    @PostMapping("/api/anime/batch-match-bangumi")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchMatchBangumi() {
        Map<String, Object> result = animeService.batchMatchBangumi();
        return ResponseEntity.ok(ApiResponse.success("批量匹配完成", result));
    }

    @Operation(summary = "获取番剧推荐")
    @GetMapping("/api/anime/recommendations")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecommendations() {
        List<Map<String, Object>> result = animeService.getRecommendations();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "获取观看热力图数据")
    @GetMapping("/api/anime/heatmap")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getHeatmap() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getHeatmap()));
    }

    @Operation(summary = "导出JSON")
    @GetMapping("/api/anime/export")
    @ResponseBody
    public ResponseEntity<String> exportJson() {
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .header("Content-Disposition", "attachment; filename=otakulog_export.json")
                .body(animeService.exportJson());
    }

    @Operation(summary = "导入JSON")
    @PostMapping("/api/anime/import")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> importJson(@RequestBody String json) {
        try {
            Map<String, Object> result = animeService.importJson(json);
            int created = (int) result.get("created");
            int updated = (int) result.get("updated");
            return ResponseEntity.ok(ApiResponse.success("已导入 " + created + " 条新记录，更新 " + updated + " 条", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "重新排序")
    @PostMapping("/api/anime/reorder")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> reorderAnime(@RequestBody List<Map<String, Object>> orders) {
        animeService.reorderAnime(orders);
        return ResponseEntity.ok(ApiResponse.success("排序已更新", null));
    }

    private AnimeStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        try {
            return AnimeStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Pageable buildPageable(int page, int size, String sortBy) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        return PageRequest.of(safePage, safeSize, SortUtil.buildSort(sortBy));
    }

}
