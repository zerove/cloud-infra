package com.cloudinfra.gateway;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

import static org.springframework.cloud.gateway.server.mvc.common.MvcUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.OPTIONS;

@EnableDiscoveryClient
// 保留 @EnableFeignClients: 网关自身路由不走 Feign(见 gatewayRouter() 的 lb:// + http()),
// 但 **framework-security 的 RemotePermissionProvider 是 @Component + @ConditionalOnClass(UserApi.class)**,
// 会被 scanBasePackages="com.cloudinfra" 扫到, 它按类型注入 UserApi ——
// 去掉本注解后网关启动即 `No qualifying bean of type 'UserApi'` (2026-10-13 e2e 实测)。
// 即"网关自身零 Feign 调用"不等于"网关不需要 Feign bean": 被扫描进来的框架组件需要。
@EnableFeignClients(basePackages = "com.cloudinfra.api")
@SpringBootApplication(
    scanBasePackages = { "com.cloudinfra" },
    exclude = {
        com.cloudinfra.framework.security.config.FrameworkSecurityAutoConfiguration.class
    }
)
@Import({RedissonConfig.class, TracingAutoConfiguration.class, PasswordEncoderConfig.class,
        GatewayApplication.TomcatRuntimeHints.class, com.cloudinfra.gateway.config.WebServerConfig.class})
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        com.cloudinfra.gateway.config.AuthWhiteListProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.SERVLET);
        app.run(args);
    }

    private static final org.springframework.web.servlet.function.RequestPredicate ALL_METHODS = 
        method(GET).or(method(POST)).or(method(PUT)).or(method(DELETE)).or(method(PATCH)).or(method(HEAD)).or(method(OPTIONS));

    @Bean
    public RouterFunction<ServerResponse> gatewayRouter() {
        return route()
                .path("/auth/**", builder -> builder
                        .before(setTargetUri("lb://cloud-auth"))
                        .route(ALL_METHODS, http()))
                .path("/system/**", builder -> builder
                        .before(setTargetUri("lb://system-module"))
                        .route(ALL_METHODS, http()))
                .path("/file/**", builder -> builder
                        .before(setTargetUri("lb://file-service"))
                        .route(ALL_METHODS, http()))
                .path("/notification/**", builder -> builder
                        .before(setTargetUri("lb://notification-service"))
                        .route(ALL_METHODS, http()))
                .path("/search/**", builder -> builder
                        .before(setTargetUri("lb://search-service"))
                        .route(ALL_METHODS, http()))
                .build();
    }

    private static java.util.function.Function<ServerRequest, ServerRequest> setTargetUri(String uri) {
        return request -> {
            request.attributes().put(GATEWAY_REQUEST_URL_ATTR, java.net.URI.create(uri));
            return request;
        };
    }

    /**
     * Runtime hints to ensure Tomcat servlet web server auto-configuration is included in native image.
     */
    static class TomcatRuntimeHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, @org.jspecify.annotations.Nullable ClassLoader classLoader) {
            hints.reflection().registerType(TomcatServletWebServerFactory.class,
                org.springframework.aot.hint.MemberCategory.values());
            hints.reflection().registerType(ServletWebServerFactory.class,
                org.springframework.aot.hint.MemberCategory.values());
            hints.reflection().registerType(WebServerFactoryCustomizer.class,
                org.springframework.aot.hint.MemberCategory.values());
            hints.reflection().registerType(TomcatServerProperties.class,
                org.springframework.aot.hint.MemberCategory.values());
            hints.resources().registerPattern("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        }
    }
}
