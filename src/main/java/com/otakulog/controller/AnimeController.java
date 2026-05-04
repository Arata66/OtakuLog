package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.dto.BatchRequest;
import com.otakulog.enums.AnimeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.otakulog.service.AiringScheduleService;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BangumiService;
import com.otakulog.service.TraceMoeService;
import com.otakulog.service.WebDavSyncService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "番剧管理", description = "番剧CRUD及查询接口")
@Controller
public class AnimeController {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private BangumiService bangumiService;

    @Autowired
    private AiringScheduleService airingScheduleService;

    @Autowired
    private TraceMoeService traceMoeService;

    @Autowired
    private WebDavSyncService webDavSyncService;

    @GetMapping("/")
    public String index(Model model) {
        List<AnimeVO> animeList = animeService.findAll();
        model.addAttribute("animeList", animeList);
        return "anime";
    }

    @Operation(summary = "添加番剧")
    @PostMapping("/api/anime/add")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> addAnime(@Valid @RequestBody AnimeDTO dto) {
        AnimeVO vo = animeService.addAnime(dto);
        return ResponseEntity.ok(ApiResponse.success("添加成功", vo));
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
                return ResponseEntity.ok(ApiResponse.error(400, "reached_max"));
            }
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
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
                return ResponseEntity.ok(ApiResponse.error(400, "reached_min"));
            }
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }

    @Operation(summary = "更新番剧信息")
    @PostMapping("/api/anime/{id}/update")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> updateAnime(
            @PathVariable Long id,
            @Valid @RequestBody AnimeUpdateDTO dto) {
        try {
            AnimeVO vo = animeService.updateAnime(id, dto);
            return ResponseEntity.ok(ApiResponse.success("更新成功", vo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
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
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "删除番剧")
    @DeleteMapping("/api/anime/{id}")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> deleteAnime(@PathVariable Long id) {
        try {
            animeService.deleteAnime(id);
            return ResponseEntity.ok(ApiResponse.success("删除成功", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
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
        Pageable pageable = PageRequest.of(page, size, buildSort(sortBy));
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
    public ResponseEntity<ApiResponse<List<AnimeVO>>> getTimeline() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getTimeline()));
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
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @Operation(summary = "搜索Bangumi")
    @GetMapping("/api/bangumi/search")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<BangumiResult>>> searchBangumi(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "8") int limit) {
        try {
            List<BangumiResult> results = bangumiService.search(keyword, limit);
            return ResponseEntity.ok(ApiResponse.success(results));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Bangumi 搜索失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi作品详情")
    @GetMapping("/api/bangumi/subject/{id}")
    @ResponseBody
    public ResponseEntity<ApiResponse<BangumiSubjectDetail>> getBangumiSubject(@PathVariable int id) {
        try {
            BangumiSubjectDetail detail = bangumiService.getSubject(id);
            return ResponseEntity.ok(ApiResponse.success(detail));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("获取作品详情失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi剧集列表")
    @GetMapping("/api/bangumi/subject/{id}/episodes")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<BangumiEpisode>>> getBangumiEpisodes(@PathVariable int id) {
        try {
            List<BangumiEpisode> episodes = bangumiService.getSubjectEpisodes(id);
            return ResponseEntity.ok(ApiResponse.success(episodes));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("获取剧集列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi放送日历")
    @GetMapping("/api/bangumi/calendar")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getBangumiCalendar() {
        try {
            List<Map<String, Object>> calendar = bangumiService.getCalendar();
            return ResponseEntity.ok(ApiResponse.success(calendar));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("获取放送日历失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "以图搜番(trace.moe)")
    @PostMapping("/api/tracemoe/search")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchByImage(@RequestParam("image") MultipartFile image) {
        try {
            Map<String, Object> result = traceMoeService.searchByImage(image);
            if (result == null) {
                return ResponseEntity.ok(ApiResponse.error("未识别到番剧"));
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("搜索失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "重新排序")
    @PostMapping("/api/anime/reorder")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> reorderAnime(@RequestBody List<Map<String, Object>> orders) {
        animeService.reorderAnime(orders);
        return ResponseEntity.ok(ApiResponse.success("排序已更新", null));
    }

    @Operation(summary = "WebDAV推送")
    @PostMapping("/api/sync/push")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncPush() {
        try {
            return ResponseEntity.ok(ApiResponse.success(webDavSyncService.push()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("推送失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "WebDAV拉取")
    @PostMapping("/api/sync/pull")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncPull() {
        try {
            return ResponseEntity.ok(ApiResponse.success(webDavSyncService.pull()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("拉取失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "WebDAV状态")
    @GetMapping("/api/sync/status")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus() {
        return ResponseEntity.ok(ApiResponse.success(webDavSyncService.getStatus()));
    }

    private AnimeStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        try {
            return AnimeStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Sort buildSort(String sortBy) {
        return switch (sortBy != null ? sortBy : "id-desc") {
            case "score-desc" -> Sort.by(Sort.Direction.DESC, "score");
            case "score-asc" -> Sort.by(Sort.Direction.ASC, "score");
            case "progress-desc" -> Sort.by(Sort.Direction.DESC, "currentEpisode");
            case "progress-asc" -> Sort.by(Sort.Direction.ASC, "currentEpisode");
            case "name-asc" -> Sort.by(Sort.Direction.ASC, "name");
            case "sortOrder-asc" -> Sort.by(Sort.Direction.ASC, "sortOrder");
            default -> Sort.by(Sort.Direction.DESC, "id");
        };
    }
}
