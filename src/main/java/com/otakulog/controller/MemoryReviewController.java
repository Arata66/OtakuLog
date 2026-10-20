package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.MemoryReviewDTO;
import com.otakulog.enums.MemoryContext;
import com.otakulog.service.MemoryReviewService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memories")
public class MemoryReviewController {
    private final MemoryReviewService service;
    public MemoryReviewController(MemoryReviewService service) { this.service = service; }

    @GetMapping
    public ApiResponse<MemoryReviewDTO> get(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long animeId, @RequestParam(required = false) MemoryContext context,
            @RequestParam(required = false) String month, @RequestParam(defaultValue = "newest") String order) {
        return ApiResponse.success(service.get(page, animeId, context, month, order));
    }

    @GetMapping("/{memoryId}")
    public ApiResponse<MemoryReviewDTO.Location> locate(@PathVariable Long memoryId) {
        return ApiResponse.success(service.locate(memoryId));
    }
}
