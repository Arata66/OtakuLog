package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.DailyWatchDTO;
import com.otakulog.service.DailyWatchService;
import com.otakulog.service.DailyAiringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DailyWatchController {
    private final DailyWatchService service;
    private final DailyAiringService airing;
    public DailyWatchController(DailyWatchService service, DailyAiringService airing) { this.service = service; this.airing = airing; }
    @GetMapping("/api/watch/daily")
    public ApiResponse<DailyWatchDTO> getDaily() { return ApiResponse.success(service.getDaily()); }
    @GetMapping("/api/watch/airing")
    public ApiResponse<DailyWatchDTO> getAiring() { return ApiResponse.success(airing.getAiring()); }
}
