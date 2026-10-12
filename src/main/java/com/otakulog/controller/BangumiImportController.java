package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.BangumiImportDTO.*;
import com.otakulog.service.BangumiImportPreviewService;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/bangumi")
public class BangumiImportController {
    private final BangumiImportPreviewService service;
    public BangumiImportController(BangumiImportPreviewService service) { this.service = service; }
    @PostMapping("/import/preview")
    public ApiResponse<Preview> preview(@RequestBody ReadRequest request, Principal principal) {
        return ApiResponse.success(service.preview(principal.getName(), request.username()));
    }
    @PostMapping("/import/confirm")
    public ApiResponse<Result> confirm(@RequestBody ConfirmRequest request, Principal principal) {
        return ApiResponse.success(service.confirm(principal.getName(), request));
    }
    @GetMapping("/collections")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(page, size));
    }
    @DeleteMapping("/collections/{key}")
    public ApiResponse<Void> remove(@PathVariable String key, @RequestParam long expectedVersion) {
        service.remove(key, expectedVersion); return ApiResponse.success(null);
    }
}
