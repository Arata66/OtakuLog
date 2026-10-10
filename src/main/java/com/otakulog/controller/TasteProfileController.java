package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.MemoryInsightDTO;
import com.otakulog.dto.TasteProfileDTO;
import com.otakulog.service.MemoryInsightService;
import com.otakulog.service.TasteProfileService;
import org.springframework.web.bind.annotation.*;

@RestController
public class TasteProfileController {
    private final MemoryInsightService insights;
    private final TasteProfileService profile;
    public TasteProfileController(MemoryInsightService insights, TasteProfileService profile) {
        this.insights = insights; this.profile = profile;
    }
    @GetMapping("/api/taste/profile")
    public ApiResponse<TasteProfileDTO> profile() { return ApiResponse.success(profile.get()); }
    @GetMapping("/api/anime/{animeId}/memories/{memoryId}/insights")
    public ApiResponse<MemoryInsightDTO> get(@PathVariable Long animeId, @PathVariable Long memoryId) {
        return ApiResponse.success(insights.get(animeId, memoryId));
    }
    @PostMapping("/api/anime/{animeId}/memories/{memoryId}/insights")
    public ApiResponse<MemoryInsightDTO.Entry> create(@PathVariable Long animeId, @PathVariable Long memoryId,
                                                      @RequestBody MemoryInsightDTO.WriteRequest request) {
        return ApiResponse.success(insights.create(animeId, memoryId, request));
    }
    @PutMapping("/api/anime/{animeId}/memories/{memoryId}/insights/{insightId}")
    public ApiResponse<MemoryInsightDTO.Entry> update(@PathVariable Long animeId, @PathVariable Long memoryId,
            @PathVariable Long insightId, @RequestBody MemoryInsightDTO.WriteRequest request) {
        return ApiResponse.success(insights.update(animeId, memoryId, insightId, request));
    }
    @DeleteMapping("/api/anime/{animeId}/memories/{memoryId}/insights/{insightId}")
    public ApiResponse<Void> delete(@PathVariable Long animeId, @PathVariable Long memoryId, @PathVariable Long insightId,
                                   @RequestParam Long expectedVersion) {
        insights.delete(animeId, memoryId, insightId, expectedVersion); return ApiResponse.success("观点已删除", null);
    }
}
