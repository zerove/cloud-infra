package com.cloudinfra.notification.config;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.core.crypto.InternalCallVerifier;
import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
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
 * 通知服务安全配置
 * 启用方法级安全与 Header 认证过滤器（从网关透传的 X-User-Id/X-User-Name 构建 SecurityContext）。
 *
 * <p>内部 Feign 契约端点（{@code /internal/**}）：HMAC 单点决策，签名无效即拒绝；
 * 网关无对应路由，终端用户不可触达（与 system-module 一致）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class NotificationSecurityConfig {

    private final HeaderAuthenticationFilter headerAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;

    @Autowired
    private InternalCallSecretProvider secretProvider;

    public NotificationSecurityConfig(HeaderAuthenticationFilter headerAuthenticationFilter,
                                      AuthenticationEntryPointImpl authenticationEntryPoint,
                                      AccessDeniedHandlerImpl accessDeniedHandler) {
        this.headerAuthenticationFilter = headerAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/management/**").permitAll()
                .requestMatchers("/notification/ws/**").permitAll()  // WebSocket 升级端点 (浏览器原生 WS 不带 Header)
                .requestMatchers("/internal/**")
                    .access((authentication, context) -> new AuthorizationDecision(
                        internalCallMatcher("/internal/**").matches(context.getRequest())))
                .anyRequest().authenticated())
            .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 复合 RequestMatcher：仅当请求路径匹配指定模式且内部签名有效时才匹配。
     * 判定统一走 {@link InternalCallVerifier}（单一真源），与 system-module 一致。
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
            String sec = secretProvider.getSecret();
            boolean ok = InternalCallVerifier.isTrusted(true, sec, sign, timestampHeader, nonce);
            if (!ok) {
                org.slf4j.LoggerFactory.getLogger(NotificationSecurityConfig.class)
                    .debug("internalCallMatcher FAIL: secret.len={} sign={} ts={} nonce={}",
                        sec == null ? 0 : sec.length(), sign, timestampHeader, nonce);
            }
            return ok;
        };
    }
}
