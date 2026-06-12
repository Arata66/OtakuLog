package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.service.WebDavSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "WebDAV 同步", description = "数据推送/拉取及同步状态")
@RestController
public class SyncApiController {

    private final WebDavSyncService webDavSyncService;

    public SyncApiController(WebDavSyncService webDavSyncService) {
        this.webDavSyncService = webDavSyncService;
    }

    @Operation(summary = "WebDAV推送")
    @PostMapping("/api/sync/push")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncPush() {
        return ResponseEntity.ok(ApiResponse.success(webDavSyncService.push()));
    }

    @Operation(summary = "WebDAV拉取")
    @PostMapping("/api/sync/pull")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncPull() {
        return ResponseEntity.ok(ApiResponse.success(webDavSyncService.pull()));
    }

    @Operation(summary = "WebDAV状态")
    @GetMapping("/api/sync/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus() {
        return ResponseEntity.ok(ApiResponse.success(webDavSyncService.getStatus()));
    }
}
