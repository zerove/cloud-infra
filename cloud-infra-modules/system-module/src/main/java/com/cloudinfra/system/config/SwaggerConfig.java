package com.cloudinfra.system.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    @Bean
    public OpenAPI systemOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("System Module API")
                        .description("System Module API Documentation")
                        .version("1.0.0"));
    }
}
