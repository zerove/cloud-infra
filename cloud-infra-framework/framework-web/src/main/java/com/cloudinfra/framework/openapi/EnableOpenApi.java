package com.cloudinfra.framework.openapi;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用统一 OpenAPI 配置 (M6-05)
 *
 * 业务模块加此注解即可自动配置统一 OpenAPI 元数据:
 * <ul>
 *   <li>title: 从 {@code spring.application.name} 推断 (默认 "Cloud-Infra {module}")</li>
 *   <li>version: 从 {@code project.version} 推断 (默认 "1.0.0")</li>
 *   <li>description: 默认 "Cloud-Infra {module} API Documentation"</li>
 *   <li>security: JWT Bearer Token (与现有 security-context 兼容)</li>
 *   <li>servers: 从 {@code springdoc.server-url} 推断 (默认 "/")</li>
 * </ul>
 *
 * 用法:
 * <pre>
 * {@code
 * @SpringBootApplication
 * @EnableOpenApi  // <-- 一行启用
 * public class SystemApplication { }
 * }
 * </pre>
 *
 * 兼容: 不加此注解时, springdoc 默认配置 (spring.application.name) 仍然生效.
 *        仅在需要 JWT bearer / 自定义 servers 时启用.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(OpenApiAutoConfiguration.class)
public @interface EnableOpenApi {
}