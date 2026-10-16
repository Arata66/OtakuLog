package com.otakulog.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.OtakuLogApplication;
import com.otakulog.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("desktop")
@ConditionalOnProperty(name = "app.desktop.managed", havingValue = "true")
public class DesktopSecurityConfig {

    private static final String CONTROL_VERIFIED = DesktopSecurityConfig.class.getName() + ".controlVerified";

    @Bean
    @ConditionalOnMissingBean(name = "desktopRuntime")
    public DesktopRuntime desktopRuntime(ObjectMapper mapper) {
        String version = OtakuLogApplication.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("受管桌面模式必须从含有应用版本的 JAR 启动");
        }
        return new DesktopRuntime(System.in, System.out, mapper, version);
    }

    @Bean
    public HttpSessionSecurityContextRepository desktopSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CookieSameSiteSupplier desktopCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofStrict().whenHasName("JSESSIONID");
    }

    @Bean
    public ServletContextInitializer desktopSessionCookie() {
        return servletContext -> servletContext.getSessionCookieConfig().setHttpOnly(true);
    }

    @Bean
    public UserDetailsService desktopUserDetailsService() {
        // 桌面身份只能经受管引导建立，不能退回自动生成密码或网站表单登录。
        return username -> { throw new UsernameNotFoundException("桌面模式不提供密码登录"); };
    }

    @Bean
    public SecurityFilterChain desktopFilterChain(HttpSecurity http, DesktopRuntime runtime,
                                                HttpSessionSecurityContextRepository repository,
                                                ObjectMapper mapper) throws Exception {
        http.cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(repository))
                .addFilterBefore(new DesktopRequestFilter(runtime, mapper), CsrfFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        request -> Boolean.TRUE.equals(request.getAttribute(CONTROL_VERIFIED))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/desktop/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(mapper, response, 401, "桌面会话尚未建立"))
                        .accessDeniedHandler((request, response, failure) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
                            writeError(mapper, response, anonymous ? 401 : 403,
                                    anonymous ? "桌面会话尚未建立" : "请求令牌无效，请刷新页面后重试");
                        }));
        return http.build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(status, message));
    }

    private static final class DesktopRequestFilter extends OncePerRequestFilter {

        private final DesktopRuntime runtime;
        private final ObjectMapper mapper;

        private DesktopRequestFilter(DesktopRuntime runtime, ObjectMapper mapper) {
            this.runtime = runtime;
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String host = "127.0.0.1:" + request.getLocalPort();
            var origins = Collections.list(request.getHeaders("Origin"));
            boolean localRequest = InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                    && Collections.list(request.getHeaders("Host")).equals(java.util.List.of(host))
                    && (origins.isEmpty() || origins.equals(java.util.List.of("http://" + host)));
            if (!localRequest) {
                writeError(mapper, response, 403, "桌面请求来源无效");
                return;
            }
            if (request.getServletPath().startsWith("/internal/desktop/")) {
                var tokens = Collections.list(request.getHeaders("X-OtakuLog-Desktop-Token"));
                if (tokens.size() != 1 || !runtime.matchesToken(tokens.get(0))) {
                    writeError(mapper, response, 403, "桌面控制凭据无效");
                    return;
                }
                request.setAttribute(CONTROL_VERIFIED, true);
            }
            chain.doFilter(request, response);
        }
    }

    public static final class WebsiteSecurityCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !context.getEnvironment().acceptsProfiles(Profiles.of("desktop"))
                    || !context.getEnvironment().getProperty("app.desktop.managed", Boolean.class, false);
        }
    }
}
