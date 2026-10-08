package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.DailyWatchDTO;
import com.otakulog.service.DailyWatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DailyWatchController {
    private final DailyWatchService service;
    public DailyWatchController(DailyWatchService service) { this.service = service; }
    @GetMapping("/api/watch/daily")
    public ApiResponse<DailyWatchDTO> getDaily() { return ApiResponse.success(service.getDaily()); }
}
