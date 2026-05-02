package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.dto.BangumiResult;
import com.otakulog.dto.BatchRequest;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.service.AnimeService;
import com.otakulog.service.BangumiService;
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

import java.util.List;
import java.util.Map;

@Controller
public class AnimeController {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private BangumiService bangumiService;

    @GetMapping("/")
    public String index(Model model) {
        List<AnimeVO> animeList = animeService.findAll();
        model.addAttribute("animeList", animeList);
        return "anime";
    }

    @PostMapping("/api/anime/add")
    @ResponseBody
    public ResponseEntity<ApiResponse<AnimeVO>> addAnime(@Valid @RequestBody AnimeDTO dto) {
        AnimeVO vo = animeService.addAnime(dto);
        return ResponseEntity.ok(ApiResponse.success("添加成功", vo));
    }

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

    @PostMapping("/api/anime/batch-delete")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> batchDelete(@RequestBody BatchRequest request) {
        animeService.batchDelete(request.getIds());
        return ResponseEntity.ok(ApiResponse.success("批量删除成功", null));
    }

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

    @GetMapping("/api/anime/stats")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getStats()));
    }

    @GetMapping("/api/anime/stats/detailed")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDetailedStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getDetailedStats()));
    }

    @GetMapping("/api/anime/stats/seasons")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSeasonStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getSeasonStats()));
    }

    @GetMapping("/api/anime/stats/enhanced")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEnhancedStats() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getEnhancedStats()));
    }

    @GetMapping("/api/anime/timeline")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<AnimeVO>>> getTimeline() {
        return ResponseEntity.ok(ApiResponse.success(animeService.getTimeline()));
    }

    @GetMapping("/api/anime/export")
    @ResponseBody
    public ResponseEntity<String> exportJson() {
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .header("Content-Disposition", "attachment; filename=otakulog_export.json")
                .body(animeService.exportJson());
    }

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
