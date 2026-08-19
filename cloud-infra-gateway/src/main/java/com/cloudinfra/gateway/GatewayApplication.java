package com.cloudinfra.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

import static org.springframework.cloud.gateway.server.mvc.common.MvcUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.OPTIONS;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.cloudinfra.api")
@SpringBootApplication(
    scanBasePackages = { "com.cloudinfra" },
    exclude = {
        com.cloudinfra.framework.security.config.FrameworkSecurityAutoConfiguration.class
    }
)
@Import({RedissonConfig.class, TracingAutoConfiguration.class, PasswordEncoderConfig.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
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
}
