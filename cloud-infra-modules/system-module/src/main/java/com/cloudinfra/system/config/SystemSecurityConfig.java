package com.cloudinfra.system.config;

import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 业务服务安全配置
 *
 * 启用 Web 安全与方法级安全，使 @PreAuthorize/@ss.hasPermi 注解真正生效。
 * 网关已执行认证并将身份注入 X-User-Id/X-User-Name Header，
 * 此处通过 HeaderAuthenticationFilter 填充 SecurityContext 并加载用户权限。
 *
 * 直连场景（绕过网关）：HeaderAuthenticationFilter 在无 Header 时放行，
 * 由各 Controller 的 @PreAuthorize 兜底（SecurityContext 为空时拒绝）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SystemSecurityConfig {

    private final HeaderAuthenticationFilter headerAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;

    public SystemSecurityConfig(
        HeaderAuthenticationFilter headerAuthenticationFilter,
        AuthenticationEntryPointImpl authenticationEntryPoint,
        AccessDeniedHandlerImpl accessDeniedHandler
    ) {
        this.headerAuthenticationFilter = headerAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex ->
                ex
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth ->
                auth
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**"
                    ).permitAll()
                    // 内部 Feign 契约端点（服务间信任调用，无用户 Header）
                    .requestMatchers(
                        "/system/user/{userId}",
                        "/system/user/username/{username}",
                        "/system/user/permissions/{userId}",
                        "/system/user/register",
                        "/system/service/**"
                    ).permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}