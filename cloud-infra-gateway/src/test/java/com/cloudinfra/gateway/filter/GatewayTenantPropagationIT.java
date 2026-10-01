package com.cloudinfra.gateway.filter;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.gateway.config.AuthWhiteListProperties;
import com.cloudinfra.gateway.domain.auth.AuthEvaluator;
import com.cloudinfra.gateway.domain.auth.SessionStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时观测: <b>真实</b> {@link SecurityFilter} 对租户头的处理。
 *
 * <h2>为什么需要这个 IT</h2>
 *
 * <p>{@code TenantIsolationEndToEndIT}（framework-mybatis）已经用真实 PostgreSQL +
 * 真实 {@code TenantSqlInterceptor} + 真实 {@code TenantContextFilter} 验证了隔离,
 * 但那里"网关转发过来的请求"是<b>测试自己手工构造并签名</b>的 ——
 * 即被观测链路的<b>网关那一半是模拟的</b>。</p>
 *
 * <p>网关这一半恰好承载了本次最关键的安全决策:</p>
 * <ul>
 *   <li>租户来自 JWT claim, 而<b>不是</b>客户端请求头</li>
 *   <li>客户端伪造的 {@code X-Tenant-Id} 必须被忽略</li>
 *   <li>缺 claim / 非法 claim 归一到 {@code default} 而不是放行 null</li>
 * </ul>
 *
 * <p>这些行为此前只有 {@code AuthEvaluatorTest} 在<b>单元层</b>断言过 {@code Identity},
 * 没有任何测试观测过 <b>过滤器实际注入了什么头</b>。</p>
 *
 * <h2>本 IT 的观测对象</h2>
 *
 * <p>驱动真实 {@link SecurityFilter}（配真实 {@link AuthEvaluator}、真实签名的真实 JWT、
 * 真实 {@link JwtUtils}）, 捕获它交给下游的 {@link IdentityRequestWrapper},
 * 直接读出转发请求上的租户头与签名头 —— 观测的是<b>过滤器真实的行为</b>而非内部字段。</p>
 *
 * <p>不需要数据库/Redis: 会话存储用实现 {@link SessionStore} 的记录型桩
 * （唯一必要的替身, 因其实现依赖 Redis）。</p>
 */
class GatewayTenantPropagationIT {

    /** 测试用签名密钥, 只在本测试内使用, 不是任何运行环境的凭据 */
    private static final String INTERNAL_SECRET = "gateway-tenant-it-secret-0123456789abcd";

    /** 测试用 JWT 密钥 (>=32 字符, 且非仓库内已知弱默认值) */
    private static final String JWT_SECRET = "gateway-tenant-it-jwt-secret-0123456789abcdef";

    /** 仅用于自洽性探针的固定密钥, 不参与任何跨组件校验 */
    private static final String SECRET_PROBE = "gateway-tenant-it-probe-secret";

    private static final String PROP_INTERNAL_CALL_SECRET = "cloud-infra.internal-call.secret";

    /** failsafe 注入的原值, 用于 @AfterEach 还原 */
    private String originalInternalCallSecret;

    @BeforeEach
    void rememberInjectedSecret() {
        originalInternalCallSecret = System.getProperty(PROP_INTERNAL_CALL_SECRET);
    }

    /**
     * 还原 failsafe 注入的密钥属性。
     *
     * <p><b>不要写成 {@code System.clearProperty}</b>: 那样会抹掉 pom.xml 的
     * {@code systemPropertyVariables} 在 JVM 启动时注入的值, 使同一 JVM 内后续需要
     * {@code InternalCallSecretProvider} 的 IT 因密钥缺失而启动失败（该 bean 是 fail-fast 的）。
     * 本项目已因此踩坑两次: {@code WebSocketIdentityTrustIT} 与
     * {@code SystemGrpcSecurityBoundaryTest}。本 IT 是 2026-10-01 新写的, 不重复同样的错。</p>
     */
    @AfterEach
    void restoreInjectedSecret() {
        if (originalInternalCallSecret == null) {
            System.clearProperty(PROP_INTERNAL_CALL_SECRET);
        } else {
            System.setProperty(PROP_INTERNAL_CALL_SECRET, originalInternalCallSecret);
        }
    }

    @Test
    @DisplayName("令牌中的租户被注入转发头, 且客户端伪造的租户头被忽略")
    void tenantComesFromTokenNotFromClientHeader() throws Exception {
        Forwarded forward = forwardWith(tokenWithTenant("tenant-A"),
            Map.of("X-Tenant-Id", "tenant-evil"));

        assertThat(forward.tenantHeader())
            .as("租户必须取自 JWT claim, 客户端头的 'tenant-evil' 不得生效")
            .isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("客户端伪造租户头且令牌无 claim 时落到 default, 而不是采用伪造值")
    void forgedHeaderDoesNotOverrideDefault() throws Exception {
        Forwarded forward = forwardWith(tokenWithTenant(null),
            Map.of("X-Tenant-Id", "tenant-evil"));

        assertThat(forward.tenantHeader())
            .as("无 claim 时网关应注入 default; 若这里等于 'tenant-evil', 说明客户端仍能自选租户")
            .isEqualTo("default");
    }

    @Test
    @DisplayName("非法 claim 归一到 default, 不原样下发")
    void illegalTenantClaimIsNormalized() throws Exception {
        Forwarded forward = forwardWith(tokenWithTenant("bad tenant; DROP TABLE--"), Map.of());

        assertThat(forward.tenantHeader())
            .as("非法标识不应被拼进下游 SQL; 归一到 default 才能保证不注入")
            .isEqualTo("default");
    }

    @Test
    @DisplayName("白名单请求无登录主体 → 不注入租户头（登录接口本身不带租户）")
    void whiteListedRequestCarriesNoTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("X-Tenant-Id", "tenant-evil");

        Map<String, String> headers = runFilter(request);

        assertThat(headers).doesNotContainKey("X-Tenant-Id");
        assertThat(headers)
            .as("白名单请求不该带身份头")
            .doesNotContainKey("X-User-Id");
    }

    @Test
    @DisplayName("网关对转发请求注入内部签名（下游据此才敢信任租户头）")
    void forwardedRequestCarriesInternalSignature() throws Exception {
        Forwarded forward = forwardWith(tokenWithTenant("tenant-A"), Map.of());

        // 下游 TenantContextFilter 依赖这三个头验签; 缺一则租户上下文不会被建立
        assertThat(forward.headers())
            .containsKeys(InternalCallCrypto.HEADER_SIGN,
                          InternalCallCrypto.HEADER_TIMESTAMP,
                          InternalCallCrypto.HEADER_NONCE);

        // 且该签名确实能用同一密钥验过 —— 否则下游的 fail-closed 会拒绝一切租户头
        assertThat(InternalCallCrypto.verify(INTERNAL_SECRET,
                forward.headers().get(InternalCallCrypto.HEADER_SIGN),
                Long.parseLong(forward.headers().get(InternalCallCrypto.HEADER_TIMESTAMP)),
                forward.headers().get(InternalCallCrypto.HEADER_NONCE)))
            .as("网关注入的签名必须能被下游验签逻辑接受")
            .isTrue();
    }

    /**
     * 守护: 真实网关连续签发 N 次, 每次的签名与它自带的时间戳都自洽。
     *
     * <p><b>为什么必须循环而不是只跑一次</b>: 曾经存在一个<b>偶发</b>生产缺陷 ——
     * {@code SecurityFilter} 里 {@code sign(secret, System.currentTimeMillis(), ...)} 与
     * {@code HEADER_TIMESTAMP = String.valueOf(System.currentTimeMillis())} 是两次独立取时。
     * 两者只在<b>恰好跨过毫秒边界</b>时才相差 1ms, 此时下游按 header 里的时间戳重算签名
     * 必然不等 → 验签失败 → 网关转发请求被 403。</p>
     *
     * <p>单次运行几乎必然通过（不跨边界），因此单次断言抓不住它。而它的实际表现是
     * <b>低流量正常、高负载偶发 403</b>，属最难归因的一类故障。</p>
     *
     * <p>走完整真实过滤器链（而非直接调 crypto），因此覆盖的是<b>生产代码路径</b>。
     * N 取得够大以让"跨毫秒边界"从偶发变成大概率。</p>
     */
    @Test
    @DisplayName("真实网关连续签发: 签名与自带时间戳始终自洽（守护偶发 403 缺陷）")
    void realGatewayEmitsSelfConsistentSignatureOnEveryCall() throws Exception {
        int rounds = 500;
        for (int i = 0; i < rounds; i++) {
            Forwarded forward = forwardWith(tokenWithTenant("tenant-A"), Map.of());
            assertThat(InternalCallCrypto.verify(INTERNAL_SECRET,
                    forward.headers().get(InternalCallCrypto.HEADER_SIGN),
                    Long.parseLong(forward.headers().get(InternalCallCrypto.HEADER_TIMESTAMP)),
                    forward.headers().get(InternalCallCrypto.HEADER_NONCE)))
                .as("第 %d/%d 次: 网关发出的签名必须与其自带的时间戳匹配。"
                    + "不匹配说明 sign 与 HEADER_TIMESTAMP 各自取了独立的 currentTimeMillis(), "
                    + "下游会拒绝该请求 (表现为低负载正常、高负载偶发 403)", i + 1, rounds)
                .isTrue();
        }
    }

    /**
     * 量化并固定"旧写法确实会失败"这一事实。
     *
     * <p>本用例<b>故意复现修复前的写法</b>（sign 与 header 各自取一次
     * {@code currentTimeMillis()}），与生产代码解耦。它断言的不是"当前代码对不对"，
     * 而是 <b>"那个偶发 403 不是我的想象，而是可稳定复现的"</b>。</p>
     *
     * <p>保留它的意义: 若将来有人质疑"这算 bug 吗、影响多大"，
     * 这个用例给出可运行的数量级答案, 而不是只能靠推测。</p>
     */
    @Test
    @DisplayName("量化: 旧写法(签名与时间戳各自取时) 在高频下必然出现验签失败")
    void legacySeparateTimestampCallsFailUnderLoad() {
        int rounds = 50_000;
        int failures = 0;

        for (int i = 0; i < rounds; i++) {
            String nonce = "nonce-" + i;
            // 复现修复前的写法
            String sign = InternalCallCrypto.sign(SECRET_PROBE, System.currentTimeMillis(), nonce);
            String headerTimestamp = String.valueOf(System.currentTimeMillis());

            if (!InternalCallCrypto.verify(SECRET_PROBE, sign,
                    Long.parseLong(headerTimestamp), nonce)) {
                failures++;
            }
        }

        assertThat(failures)
            .as("旧写法在 %d 次中应出现若干次失败 (实测得到该数字即为缺陷影响面); "
                + "若恒为 0, 说明本用例已无法复现历史缺陷, 应重新评估该结论", rounds)
            .isPositive();

        // 把实测数量打印到报告里: 后人评估"这个缺陷影响多大"时有可运行的数据,
        // 而不必重新推算或依赖口述
        System.out.printf("[量化] 旧写法(sign/header 各自取时) 验签失败 %d/%d 次 = %.4f%%%n",
            failures, rounds, 100.0 * failures / rounds);
    }

    // ========== 真实组件装配 ==========

    private static Forwarded forwardWith(String token, Map<String, String> clientHeaders) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/post/list");
        request.addHeader("Authorization", "Bearer " + token);
        clientHeaders.forEach(request::addHeader);

        Map<String, String> headers = runFilter(request);

        assertThat(headers)
            .as("转发请求应带身份头, 否则说明过滤器没走到身份传播那一步")
            .containsKey("X-User-Id");

        return new Forwarded(headers);
    }

    /** 跑一次真实的 SecurityFilter, 返回它交给下游的头 */
    private static Map<String, String> runFilter(MockHttpServletRequest request) throws Exception {
        JwtUtils jwtUtils = realJwtUtils();
        AuthEvaluator evaluator = realEvaluator(jwtUtils);
        SecurityFilter filter = realFilter(evaluator);

        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> captured = new HashMap<>();

        FilterChain chain = (req, res) -> {
            if (req instanceof IdentityRequestWrapper wrapper) {
                // 观测点: 真实过滤器包装出的转发请求上实际存在的头
                for (String name : List.of("X-User-Id", "X-User-Name", "X-Tenant-Id",
                        InternalCallCrypto.HEADER_SIGN,
                        InternalCallCrypto.HEADER_TIMESTAMP,
                        InternalCallCrypto.HEADER_NONCE)) {
                    String value = wrapper.getHeader(name);
                    if (value != null) {
                        captured.put(name, value);
                    }
                }
            }
        };

        filter.doFilter(request, response, chain);
        return captured;
    }

    private static AuthEvaluator realEvaluator(JwtUtils jwtUtils) {
        AuthWhiteListProperties whiteList = new AuthWhiteListProperties();
        whiteList.setWhiteList(List.of("/auth/login", "/auth/register"));
        return new AuthEvaluator(new AlwaysValidSessionStore(), whiteList, jwtUtils);
    }

    private static SecurityFilter realFilter(AuthEvaluator evaluator) throws Exception {
        SecurityFilter filter = new SecurityFilter();
        ReflectionTestUtils.setField(filter, "authEvaluator", evaluator);

        // 用真实 InternalCallSecretProvider 走一遍密钥加载路径 (@PostConstruct -> resolveSecret),
        // 而不是直接把 secret 字符串塞进过滤器 —— 后者会绕过密钥解析这一环
        ReflectionTestUtils.setField(filter, "secretProvider", realSecretProvider());
        ReflectionTestUtils.invokeMethod(filter, "initSecret");

        return filter;
    }

    /** 真实密钥持有者: 由 InternalCallCrypto.resolveSecret() 解析测试用密钥 */
    private static InternalCallSecretProvider realSecretProvider() {
        System.setProperty(PROP_INTERNAL_CALL_SECRET, INTERNAL_SECRET);
        InternalCallSecretProvider provider = new InternalCallSecretProvider();
        ReflectionTestUtils.invokeMethod(provider, "init");
        return provider;
    }

    private static JwtUtils realJwtUtils() {
        JwtUtils jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "expireTime", 3_600_000L);
        ReflectionTestUtils.invokeMethod(jwtUtils, "init");
        return jwtUtils;
    }

    /** 用真实 JwtUtils 签一枚带指定租户的令牌 */
    private static String tokenWithTenant(String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", 10001L);
        claims.put("jti", UUID.randomUUID().toString());
        if (tenantId != null) {
            claims.put("tenantId", tenantId);
        }
        return realJwtUtils().createToken(claims, "tester");
    }

    // ========== 辅助 ==========

    private record Forwarded(Map<String, String> headers) {
        String tenantHeader() {
            return headers.get("X-Tenant-Id");
        }
    }

    /**
     * 会话存储桩: 一律放行。
     *
     * <p>这是本 IT 唯一的替身 —— 真实实现 {@code RedisSessionStore} 依赖 Redis。
     * 它不做任何与租户相关的事, 因此不影响被观测的租户行为。</p>
     */
    private static final class AlwaysValidSessionStore implements SessionStore {
        @Override
        public boolean isBlacklisted(String jti) {
            return false;
        }

        @Override
        public boolean validateSession(Long userId, String jti) {
            return true;
        }

        @Override
        public void markAsBlacklisted(String jti, long ttlSeconds) {
            // no-op
        }
    }
}