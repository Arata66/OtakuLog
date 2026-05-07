package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.service.BangumiService;
import com.otakulog.service.TraceMoeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "Bangumi & 搜图", description = "Bangumi 搜索/详情及 trace.moe 以图搜番")
@RestController
public class BangumiApiController {

    private final BangumiService bangumiService;
    private final TraceMoeService traceMoeService;

    public BangumiApiController(BangumiService bangumiService, TraceMoeService traceMoeService) {
        this.bangumiService = bangumiService;
        this.traceMoeService = traceMoeService;
    }

    @Operation(summary = "搜索Bangumi")
    @GetMapping("/api/bangumi/search")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> searchBangumi(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "8") int limit) {
        try {
            List<BangumiResult> results = bangumiService.search(keyword, limit);
            return ResponseEntity.ok(ApiResponse.success(results));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "Bangumi 搜索失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi作品详情")
    @GetMapping("/api/bangumi/subject/{id}")
    public ResponseEntity<ApiResponse<BangumiSubjectDetail>> getBangumiSubject(@PathVariable int id) {
        try {
            BangumiSubjectDetail detail = bangumiService.getSubject(id);
            return ResponseEntity.ok(ApiResponse.success(detail));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "获取作品详情失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi剧集列表")
    @GetMapping("/api/bangumi/subject/{id}/episodes")
    public ResponseEntity<ApiResponse<List<BangumiEpisode>>> getBangumiEpisodes(@PathVariable int id) {
        try {
            List<BangumiEpisode> episodes = bangumiService.getSubjectEpisodes(id);
            return ResponseEntity.ok(ApiResponse.success(episodes));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "获取剧集列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取Bangumi放送日历")
    @GetMapping("/api/bangumi/calendar")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getBangumiCalendar() {
        try {
            List<Map<String, Object>> calendar = bangumiService.getCalendar();
            return ResponseEntity.ok(ApiResponse.success(calendar));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "获取放送日历失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "Bangumi排行榜")
    @GetMapping("/api/bangumi/rankings")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> getRankings(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            List<BangumiResult> results = bangumiService.getSubjectRankings(sort, limit, offset);
            return ResponseEntity.ok(ApiResponse.success(results));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "获取排行榜失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "当季新番列表")
    @GetMapping("/api/bangumi/season")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> getSeasonAnime(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            List<BangumiResult> results = bangumiService.getSeasonAnime(sort, limit, offset);
            return ResponseEntity.ok(ApiResponse.success(results));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "获取季度列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "以图搜番(trace.moe)")
    @PostMapping("/api/tracemoe/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchByImage(@RequestParam("image") MultipartFile image) {
        try {
            Map<String, Object> result = traceMoeService.searchByImage(image);
            if (result == null) {
                return ResponseEntity.status(404).body(ApiResponse.error(404, "未识别到番剧"));
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(502, "搜索失败: " + e.getMessage()));
        }
    }
}
