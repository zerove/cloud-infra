package com.cloudinfra.auth.config;

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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    public SecurityConfig(
        AuthenticationEntryPointImpl authenticationEntryPoint,
        AccessDeniedHandlerImpl accessDeniedHandler,
        HeaderAuthenticationFilter headerAuthenticationFilter
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.headerAuthenticationFilter = headerAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
        throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex ->
                ex
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth ->
                auth
                    .requestMatchers("/auth/**")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        // 管理端点 (ADR-008: 管理端口独立, base-path=/management)
                        "/management/health",
                        "/management/info",
                        "/management/prometheus"
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .addFilterBefore(
                headerAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }
}
