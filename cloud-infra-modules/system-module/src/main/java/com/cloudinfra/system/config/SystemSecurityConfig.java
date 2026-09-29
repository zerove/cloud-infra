package com.cloudinfra.system.config;

import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 业务服务安全配置
 *
 * 启用 Web 安全与方法级安全，使 @PreAuthorize/@ss.hasPermi 注解真正生效。
 * 网关已执行认证并将身份注入 X-User-Id/X-User-Name Header，
 * 此处通过 HeaderAuthenticationFilter 填充 SecurityContext 并加载用户权限。
 *
 * 直连场景（绕过网关）：HeaderAuthenticationFilter 在无 Header 时放行，
 * 由各 Controller 的 @PreAuthorize 兜底（SecurityContext 为空时拒绝）。
 *
 * 内部 Feign 契约端点：仅当请求携带基于共享密钥的 HMAC 签名（含时间戳/随机数）
 * 且签名有效时才放行，防止外部伪造内部调用标识。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SystemSecurityConfig {

    private final HeaderAuthenticationFilter headerAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;

    @Autowired
    private InternalCallSecretProvider secretProvider;
    // A3 关键修复: 之前用 field 缓存 secret, lambda capture this.field 引用,
    // field 初始化 vs filterChain @Bean 调用顺序问题导致 null 值, 验签失败.
    // 改用 secretProvider.getSecret() 实时调, 保证用最新值.

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
                        "/swagger-resources/**",
                        // 管理端点 (ADR-008: 管理端口独立, base-path=/management)
                        "/management/health",
                        "/management/info",
                        "/management/prometheus"
                    ).permitAll()
                    // 内部 Feign 契约端点：仅允许签名有效的内部调用
                    .requestMatchers(internalCallMatcher(
                        "/system/user/{userId}",
                        "/system/user/username/{username}",
                        "/system/user/permissions/{userId}",
                        "/system/user/detail/{userId}",
                        "/system/user/credential/{userId}",
                        "/system/user/credential/username/{username}",
                        "/system/user/register",
                        "/system/service/**"
                    )).permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 复合 RequestMatcher：仅当请求路径匹配指定模式 且 内部签名有效时才匹配。
     * 外部请求经网关时签名头被置空，因此无法伪造。
     */
    private RequestMatcher internalCallMatcher(String... antPatterns) {
        PathPatternRequestMatcher[] pathMatchers =
            java.util.Arrays.stream(antPatterns)
                .map(PathPatternRequestMatcher::pathPattern)
                .toArray(PathPatternRequestMatcher[]::new);

        OrRequestMatcher pathMatcher = new OrRequestMatcher(pathMatchers);

        return request -> {
            if (!pathMatcher.matches(request)) {
                return false;
            }
            String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);
            String timestampHeader = request.getHeader(InternalCallCrypto.HEADER_TIMESTAMP);
            String nonce = request.getHeader(InternalCallCrypto.HEADER_NONCE);
            if (timestampHeader == null || nonce == null) {
                return false;
            }
            try {
                long timestamp = Long.parseLong(timestampHeader);
                String sec = secretProvider.getSecret();
                boolean ok = InternalCallCrypto.verify(sec, sign, timestamp, nonce);
                if (!ok) {
                    org.slf4j.LoggerFactory.getLogger(SystemSecurityConfig.class)
                        .debug("internalCallMatcher FAIL: secret.len={} sign={} ts={} nonce={}",
                            sec==null?0:sec.length(), sign, timestamp, nonce);
                }
                return ok;
            } catch (NumberFormatException e) {
                return false;
            }
        };
    }
}