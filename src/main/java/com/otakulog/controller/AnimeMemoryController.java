package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnimeMemoryDTO;
import com.otakulog.service.AnimeMemoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/anime/{id}/memories")
public class AnimeMemoryController {
    private final AnimeMemoryService service;
    public AnimeMemoryController(AnimeMemoryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<AnimeMemoryDTO> get(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.get(id, page, size));
    }
    @GetMapping("/{memoryId}")
    public ApiResponse<AnimeMemoryDTO.Entry> getEntry(@PathVariable Long id, @PathVariable Long memoryId) {
        return ApiResponse.success(service.getEntry(id, memoryId));
    }
    @PostMapping
    public ApiResponse<AnimeMemoryDTO.Entry> create(@PathVariable Long id, @RequestBody AnimeMemoryDTO.WriteRequest request) {
        return ApiResponse.success(service.create(id, request));
    }
    @PutMapping("/{memoryId}")
    public ApiResponse<AnimeMemoryDTO.Entry> update(@PathVariable Long id, @PathVariable Long memoryId,
                                                   @RequestBody AnimeMemoryDTO.WriteRequest request) {
        return ApiResponse.success(service.update(id, memoryId, request));
    }
    @DeleteMapping("/{memoryId}")
    public ApiResponse<Void> delete(@PathVariable Long id, @PathVariable Long memoryId, @RequestParam Long expectedVersion) {
        service.delete(id, memoryId, expectedVersion);
        return ApiResponse.success("记忆已删除", null);
    }
}
