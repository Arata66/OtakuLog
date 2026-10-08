package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.EpisodeHistoryDTO;
import com.otakulog.service.EpisodeHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/anime/{id}/episodes")
public class EpisodeHistoryController {
    private final EpisodeHistoryService service;
    public EpisodeHistoryController(EpisodeHistoryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<EpisodeHistoryDTO> get(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.get(id, page, size));
    }

    @PutMapping("/{number}")
    public ApiResponse<Void> update(@PathVariable Long id, @PathVariable int number,
                                   @RequestBody EpisodeHistoryDTO.UpdateRequest request) {
        service.update(id, number, request);
        return ApiResponse.success("观看日期已保存", null);
    }
}
