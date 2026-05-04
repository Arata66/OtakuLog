package com.otakulog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI otakuLogOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("OtakuLog API")
                .description("追番日记 API 文档")
                .version("1.0.0"));
    }
}
