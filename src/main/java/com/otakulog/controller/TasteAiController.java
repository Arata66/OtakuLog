package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.TasteAiDTO.*;
import com.otakulog.service.TasteAiService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class TasteAiController {
    private final TasteAiService ai;
    public TasteAiController(TasteAiService ai) { this.ai = ai; }
    @PostMapping("/api/anime/{animeId}/memories/{memoryId}/insights/ai/preview")
    public ApiResponse<Preview> preview(@PathVariable Long animeId, @PathVariable Long memoryId, @RequestBody Request request) {
        return ApiResponse.success(ai.preview(animeId, memoryId, request));
    }
    @PostMapping("/api/anime/{animeId}/memories/{memoryId}/insights/ai/extract")
    public ResponseEntity<ApiResponse<Result>> extract(@PathVariable Long animeId, @PathVariable Long memoryId, @RequestBody Request request) {
        Result result = ai.extract(animeId, memoryId, request);
        int status = switch (result.status()) { case FAILED -> 502; case STALE -> 409; default -> 200; };
        return ResponseEntity.status(status).body(new ApiResponse<>(status, result.message(), result));
    }
    @GetMapping("/api/taste/ai/calls/{requestKey}")
    public ApiResponse<Result> get(@PathVariable String requestKey) { return ApiResponse.success(ai.get(requestKey)); }
    @GetMapping("/api/taste/ai/usage")
    public ApiResponse<UsageSummary> usage() { return ApiResponse.success(ai.usage()); }
    // 绑定异常链可能包含所选感想，AI 入口只回固定提示并且不记录异常正文。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson() {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "请求格式错误，请提供严格整数版本与文字选择"));
    }
}
