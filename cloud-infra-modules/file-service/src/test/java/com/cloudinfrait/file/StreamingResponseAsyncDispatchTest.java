package com.cloudinfrait.file;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现并锁定 {@code StreamingResponseBody} 下载的异步重派发鉴权缺陷。
 *
 * <p>缺陷机制（2026-10-06 由生产日志逐帧确认，非推断）：
 * <ol>
 *   <li>Controller 方法正常执行完（生产日志首行：
 *       {@code Status: 200 | Duration: 26ms | User: 1}），返回
 *       {@link StreamingResponseBody} 后响应已提交；</li>
 *   <li>Tomcat 异步派发（{@code AsyncRunnable.run → doInternalDispatch}）<b>重跑整条过滤链</b>；</li>
 *   <li>真实 {@code HeaderAuthenticationFilter extends OncePerRequestFilter} 未覆写
 *       {@code shouldNotFilterAsyncDispatch}，<b>在异步派发中不再执行</b>
 *       —— 生产堆栈里该过滤器出现 <b>0 次</b>；</li>
 *   <li>{@code SecurityContextHolder} 默认 {@code MODE_THREADLOCAL}，异步是新线程
 *       ⇒ 上下文为空 ⇒ {@code anyRequest().authenticated()} 判否
 *       ⇒ {@code AuthorizationDeniedException: Access Denied}；</li>
 *   <li>响应已提交，无法再写 401 ⇒ 半截 chunk 传给网关 ⇒ 网关侧表现为
 *       {@code Premature end of chunk coded message body}。</li>
 * </ol>
 *
 * <p><b>为何要新写而不用现成的</b>：{@code FileHttpFlowIntegrationTest} 用的是
 * {@code HeaderAuthenticationFilter} 的<b>匿名子类</b>并整体覆写
 * {@code doFilterInternal}，绕开了 {@code OncePerRequestFilter} 的异步语义，
 * 因此它测不到本缺陷 —— 缺陷能长期存在正是因为如此。
 * 本测试使用<b>真实</b>过滤器实例，并保留生产同款 HMAC 签名认证路径。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {StreamingResponseAsyncDispatchTest.StreamFixtureApp.class,
                StreamingResponseAsyncDispatchTest.TestSecurityConfig.class},
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "cloud-infra.security.verify-internal-sign=true",
                // RedissonConfig 是无条件 @AutoConfiguration (bean 创建即连 localhost:6379),
                // 靠 imports 而非组件扫描加载 —— 所以收窄 scanBasePackages 拦不住它, 只有 exclude 有效。
                // 含 Redis 的端到端由 FileHttpFlowIntegrationTest 经 Testcontainers 覆盖。
                "spring.autoconfigure.exclude="
                        + "com.cloudinfra.framework.redis.config.RedissonConfig"
})
@ActiveProfiles("test")
class StreamingResponseAsyncDispatchTest {

    private static final String SECRET = "async-dispatch-test-secret";
    private static final String USER_ID = "1";
    private static final byte[] CONTENT = "async-dispatch-stream-payload".getBytes();

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
        }
        return client;
    }

    /** 附加生产同款 HMAC 签名头（网关注入身份时的真实形态）。 */
    private WebTestClient.RequestHeadersSpec<?> signed(String path, String noncePrefix) {
        long ts = System.currentTimeMillis();
        String nonce = noncePrefix + "-" + ts;
        return client().get().uri(path)
                .header("X-User-Id", USER_ID)
                .header("X-User-Name", "async_user")
                .header(InternalCallCrypto.HEADER_SIGN, InternalCallCrypto.sign(SECRET, ts, nonce))
                .header(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts))
                .header(InternalCallCrypto.HEADER_NONCE, nonce);
    }

    @Test
    void streamingDownloadSucceedsForSignedAuthenticatedUser() {
        byte[] body = signed("/fixture/stream", "stream")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).isEqualTo(CONTENT);
    }

    @Test
    void nonStreamingEndpointWorks() {
        // 对照组：非流式端点不受异步重派发影响。
        // 它与流式端点共享同一套过滤器与授权规则，唯一差别是响应是否异步写出，
        // 因此两者结果不同即可把缺陷范围锁定在 StreamingResponseBody 上。
        signed("/fixture/plain", "plain")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void streamingDownloadWithoutIdentityIsRejected() {
        // 负向对照：没有 X-User-Id 时必须被拒。
        // 防止"让过滤器在异步派发重跑"被实现成"异步派发时无条件放行"。
        client().get().uri("/fixture/stream")
                .exchange()
                .expectStatus().isOk()   // 入口点返回 HTTP 200 + 业务码
                .expectBody()
                .jsonPath("$.code").isEqualTo(401);
    }

    @SpringBootApplication
    @Import({StreamController.class})
    static class StreamFixtureApp {
    }

    @RestController
    static class StreamController {

        @GetMapping("/fixture/stream")
        ResponseEntity<StreamingResponseBody> stream() {
            StreamingResponseBody body = out -> out.write(CONTENT);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body);
        }

        @GetMapping("/fixture/plain")
        String plain() {
            return "ok";
        }
    }

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        public AuthenticationEntryPointImpl entryPoint() {
            return new AuthenticationEntryPointImpl();
        }

        @Bean
        public AccessDeniedHandlerImpl deniedHandler() {
            return new AccessDeniedHandlerImpl();
        }

        /**
         * 真实 {@code InternalCallSecretProvider} 是普通类（非接口），
         * 且 {@code @PostConstruct init()} 走 {@code InternalCallCrypto.resolveSecret()}，
         * 只认环境变量 {@code INTERNAL_CALL_SECRET} 或系统属性
         * {@code -Dcloud-infra.internal-call.secret}（见 AGENTS.md「密钥注入」），
         * 二者都不是 Spring Environment。
         * 因此这里用系统属性注入，{@code @SpringBootTest properties} 对它<b>无效</b>。
         */
        @Bean
        public com.cloudinfra.common.core.crypto.InternalCallSecretProvider secretProvider() {
            System.setProperty("cloud-infra.internal-call.secret", SECRET);
            return new com.cloudinfra.common.core.crypto.InternalCallSecretProvider();
        }

        @Bean
        public IPermissionProvider permissionProvider() {
            return new IPermissionProvider() {
                @Override
                public List<String> getPermissionsByUserId(Long userId) {
                    return List.of("fixture:stream:read");
                }

                @Override
                public void clearPermissionCache(Long userId) {
                    // 测试不需要清缓存
                }
            };
        }

        /**
         * 注册<b>真实</b> {@link HeaderAuthenticationFilter}。
         *
         * <p>刻意不加 {@code @Bean}：Filter 类型的 bean 会被 Spring Boot 注册为容器级
         * 全局过滤器，先于 security 链执行，随后 {@code SecurityContextHolderFilter}
         * 用空的 {@code SecurityContextRepository} 重置 ThreadLocal，导致认证凭空丢失。
         * 与 {@code FileHttpFlowIntegrationTest} 相同的规避手法。
         */
        private static final HeaderAuthenticationFilter REAL_HEADER_FILTER =
                new HeaderAuthenticationFilter();

        @Bean
        public SecurityFilterChain testFilterChain(HttpSecurity http,
                                                   AuthenticationEntryPointImpl entryPoint,
                                                   AccessDeniedHandlerImpl deniedHandler)
                throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/actuator/**").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(REAL_HEADER_FILTER, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }
}
