package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.common.ApiResponse;
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

@RestController
@RequestMapping("/internal/desktop")
@Profile("desktop")
@ConditionalOnProperty(name = "app.desktop.managed", havingValue = "true")
public class DesktopControlController {

    private final DesktopRuntime runtime;
    private final HttpSessionSecurityContextRepository repository;
    private final ObjectMapper mapper;

    public DesktopControlController(DesktopRuntime runtime, HttpSessionSecurityContextRepository repository, ObjectMapper mapper) {
        this.runtime = runtime;
        this.repository = repository;
        this.mapper = mapper;
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
