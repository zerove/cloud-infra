package com.cloudinfra.framework.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Locale;

/**
 * OpenAPI 自动配置 (M6-05)
 *
 * 启用条件: {@link EnableOpenApi} 注解 (业务模块主类加注解即装载).
 * 装配内容:
 * - Info 元数据 (title/version/description/contact/license)
 * - JWT Bearer security scheme
 * - 默认 server URL
 *
 * 非破坏: 业务模块未加 @EnableOpenApi 时, springdoc 默认行为不变 (沿用 spring.application.name).
 */
@Configuration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiAutoConfiguration {

    private static final String BEARER_SCHEME_NAME = "BearerAuth";

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI cloudInfraOpenApi(
        Environment env,
        @Value("${spring.application.name:cloud-infra-module}") String appName,
        @Value("${springdoc.server-url:/}") String serverUrl,
        @Value("${cloud-infra.version:1.0.0}") String version,
        @Value("${cloud-infra.openapi.contact-email:dev@cloudinfra.com}") String contactEmail
    ) {
        String moduleName = extractModuleName(appName);
        String title = "Cloud-Infra " + moduleName;
        String description = title + " API Documentation";

        return new OpenAPI()
            .info(new Info()
                .title(title)
                .description(description)
                .version(version)
                .contact(new Contact().name("Cloud-Infra Team").email(contactEmail))
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0"))
            )
            .servers(List.of(new Server().url(serverUrl).description("Default Server")))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME_NAME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT Bearer Token (从 X-User-Id/Authorization 提取)"))
            )
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }

    /**
     * 从 spring.application.name 提取模块名 (如 "cloud-system" → "System").
     */
    private String extractModuleName(String appName) {
        if (appName == null || appName.isBlank()) {
            return "Module";
        }
        int dash = appName.lastIndexOf('-');
        if (dash < 0 || dash == appName.length() - 1) {
            return appName;
        }
        String suffix = appName.substring(dash + 1);
        return suffix.substring(0, 1).toUpperCase(Locale.ROOT) + suffix.substring(1);
    }
}