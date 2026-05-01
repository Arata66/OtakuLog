package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeDTO;
import com.otakulog.dto.AnimeUpdateDTO;
import com.otakulog.dto.AnimeVO;
import com.otakulog.enums.AnimeStatus;
import com.otakulog.service.AnimeService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @GetMapping("/api/anime/search")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<AnimeVO>>> searchAnime(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "id-desc") String sortBy) {

        AnimeStatus animeStatus = parseStatus(status);
        List<AnimeVO> results = animeService.searchAnime(name, animeStatus, sortBy);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @GetMapping("/api/anime/page")
    @ResponseBody
    public ResponseEntity<ApiResponse<Page<AnimeVO>>> searchAnimePaged(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        AnimeStatus animeStatus = parseStatus(status);
        Pageable pageable = PageRequest.of(page, size);
        Page<AnimeVO> results = animeService.searchAnimePaged(name, animeStatus, pageable);
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

    private AnimeStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        try {
            return AnimeStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
