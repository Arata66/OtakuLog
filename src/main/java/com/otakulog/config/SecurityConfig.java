package com.otakulog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otakulog.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var apiRequests = new AntPathRequestMatcher("/api/**");
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        http
            .cors(cors -> {})
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/error",
                    "/manifest.json", "/sw.js", "/icons/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    if (apiRequests.matches(request)) {
                        writeApiError(mapper, response, 401, "登录已失效，请重新登录");
                    } else {
                        loginEntryPoint.commence(request, response, exception);
                    }
                })
                .accessDeniedHandler((request, response, exception) -> {
                    if (!apiRequests.matches(request)) {
                        response.sendError(403);
                        return;
                    }
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    boolean anonymous = authentication == null
                            || authentication instanceof AnonymousAuthenticationToken;
                    writeApiError(mapper, response, anonymous ? 401 : 403,
                            anonymous ? "登录已失效，请重新登录" : "请求令牌无效，请刷新页面后重试");
                })
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );
        return http.build();
    }

    private void writeApiError(ObjectMapper mapper, HttpServletResponse response,
                               int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(status, message));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        manager.createUser(User.withUsername(adminUsername)
                .password(encoder.encode(adminPassword))
                .roles("USER")
                .build());
        return manager;
    }
}
