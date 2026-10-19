package com.otakulog.desktop;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.common.ApiResponse;
import com.otakulog.common.ConflictException;
import com.otakulog.config.TasteAiRuntime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/internal/desktop")
@Profile("desktop")
@ConditionalOnProperty(name = "app.desktop.managed", havingValue = "true")
public class DesktopControlController {

    private static final int MAX_AI_SETTINGS_BYTES = 4096;

    private final DesktopRuntime runtime;
    private final HttpSessionSecurityContextRepository repository;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final TasteAiRuntime ai;

    public DesktopControlController(DesktopRuntime runtime, HttpSessionSecurityContextRepository repository, ObjectMapper mapper, TasteAiRuntime ai) {
        this.runtime = runtime;
        this.repository = repository;
        this.mapper = mapper;
        this.strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.ai = ai;
    }

    @PostMapping("/ai-settings")
    public ResponseEntity<?> aiSettings(HttpServletRequest request) {
        return settingsRequest(request, root -> {
            if (root.size() != 3 || !root.path("enabled").isBoolean() || !root.path("apiKey").isTextual()
                    || !root.path("dailyLimit").isIntegralNumber() || !root.path("dailyLimit").canConvertToInt())
                throw new IllegalArgumentException("AI 配置格式无效");
            return ai.apply(root.path("enabled").booleanValue(), root.path("apiKey").textValue(), root.path("dailyLimit").intValue());
        });
    }

    @PostMapping("/ai-settings-lock")
    public ResponseEntity<?> lockAiSettings(HttpServletRequest request) {
        return settingsRequest(request, root -> {
            if (!root.isEmpty()) throw new IllegalArgumentException("AI 配置格式无效");
            return ai.lockSettings();
        });
    }

    @PostMapping("/ai-settings-cancel")
    public ResponseEntity<?> cancelAiSettings(HttpServletRequest request) {
        return settingsRequest(request, root -> {
            if (!root.isEmpty()) throw new IllegalArgumentException("AI 配置格式无效");
            return ai.cancelSettings();
        });
    }

    private ResponseEntity<?> settingsRequest(HttpServletRequest request, Function<JsonNode, TasteAiRuntime.Status> operation) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_AI_SETTINGS_BYTES + 1);
            if (body.length > MAX_AI_SETTINGS_BYTES) throw new IllegalArgumentException("AI 配置格式无效");
            try (var parser = strictMapper.createParser(body)) {
                JsonNode root = strictMapper.readTree(parser);
                if (root == null || !root.isObject() || parser.nextToken() != null) throw new IllegalArgumentException("AI 配置格式无效");
                return ResponseEntity.ok(ApiResponse.success(operation.apply(root)));
            }
        } catch (ConflictException failure) {
            return ResponseEntity.status(409).body(ApiResponse.error(409, "AI 整理或配置正在进行，请稍后重试"));
        } catch (IOException | IllegalArgumentException failure) {
            // 解析异常可能含密钥原文，控制面只返回固定提示且不记录异常链。
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "AI 配置格式无效"));
        } catch (RuntimeException failure) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(500, "AI 配置保存失败"));
        }
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(HttpServletRequest request, HttpServletResponse response) {
        if (!runtime.claimBootstrap()) {
            return ResponseEntity.status(409).body(ApiResponse.error(409, "桌面会话已经引导，不能重复建立"));
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("desktop-local", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        // 每次进程只引导一次，不能因闲置过期而使桌面身份无法恢复。
        request.getSession(true).setMaxInactiveInterval(-1);
        request.changeSessionId();
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        return ResponseEntity.ok(ApiResponse.success(Map.of("username", "desktop-local")));
    }

    @PostMapping("/shutdown")
    public void shutdown(HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.success("正在退出", null));
        response.flushBuffer();
        runtime.shutdown();
    }
}
