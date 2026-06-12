package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.BangumiEpisode;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BangumiSubjectDetail;
import com.otakulog.service.AnimeService;
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
    private final AnimeService animeService;

    public BangumiApiController(BangumiService bangumiService, TraceMoeService traceMoeService, AnimeService animeService) {
        this.bangumiService = bangumiService;
        this.traceMoeService = traceMoeService;
        this.animeService = animeService;
    }

    @Operation(summary = "搜索Bangumi")
    @GetMapping("/api/bangumi/search")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> searchBangumi(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "8") int limit) {
        List<BangumiResult> results = bangumiService.search(keyword, limit);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @Operation(summary = "获取Bangumi作品详情")
    @GetMapping("/api/bangumi/subject/{id}")
    public ResponseEntity<ApiResponse<BangumiSubjectDetail>> getBangumiSubject(@PathVariable int id) {
        BangumiSubjectDetail detail = bangumiService.getSubject(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    @Operation(summary = "获取Bangumi剧集列表")
    @GetMapping("/api/bangumi/subject/{id}/episodes")
    public ResponseEntity<ApiResponse<List<BangumiEpisode>>> getBangumiEpisodes(@PathVariable int id) {
        List<BangumiEpisode> episodes = bangumiService.getSubjectEpisodes(id);
        return ResponseEntity.ok(ApiResponse.success(episodes));
    }

    @Operation(summary = "获取Bangumi放送日历")
    @GetMapping("/api/bangumi/calendar")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getBangumiCalendar() {
        List<Map<String, Object>> calendar = bangumiService.getCalendar();
        return ResponseEntity.ok(ApiResponse.success(calendar));
    }

    @Operation(summary = "Bangumi排行榜")
    @GetMapping("/api/bangumi/rankings")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> getRankings(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<BangumiResult> results = bangumiService.getSubjectRankings(sort, limit, offset);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @Operation(summary = "当季新番列表")
    @GetMapping("/api/bangumi/season")
    public ResponseEntity<ApiResponse<List<BangumiResult>>> getSeasonAnime(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<BangumiResult> results = bangumiService.getSeasonAnime(sort, limit, offset);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @Operation(summary = "以图搜番(trace.moe)")
    @PostMapping("/api/tracemoe/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchByImage(@RequestParam("image") MultipartFile image) {
        Map<String, Object> result = traceMoeService.searchByImage(image);
        if (result == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "未识别到番剧"));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "从Bangumi导入追番记录")
    @PostMapping("/api/bangumi/import/{username}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importFromBangumi(@PathVariable String username) {
        Map<String, Object> result = animeService.importFromBangumi(username);
        int created = (int) result.get("created");
        int skipped = (int) result.get("skipped");
        return ResponseEntity.ok(ApiResponse.success("已导入 " + created + " 条，跳过 " + skipped + " 条", result));
    }
}
