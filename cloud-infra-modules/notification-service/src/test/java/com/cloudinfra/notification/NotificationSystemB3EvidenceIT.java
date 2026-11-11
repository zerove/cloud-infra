package com.cloudinfra.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S4 运行时证据（notification + system）：B3 兼容/隔离 + 内部 HMAC 审计契约 + 审计聚合链路。
 *
 * <p>opt-in：仅在 {@code -Devidence.it=true} 且所需参数齐全时执行真实 HTTP 断言；
 * 缺参通过 {@code assume} 跳过（不伪造通过）。</p>
 *
 * <p>判定口径（<b>按实现读出来的，不按直觉猜</b>）：
 * <ul>
 *   <li>框架的 {@code AuthenticationEntryPointImpl} / {@code AccessDeniedHandlerImpl} 都返回
 *       <b>HTTP 200 + {@code Result.fail(401/403, ...)}</b>（不是 HTTP 401/403）；
 *       controller 内直接 {@code Result.fail(403, "…请使用 /notification/my/history")} 同样是 HTTP 200。
 *       故判定"被拒绝"必须看<b>响应体里的 code</b>，不能只看 HTTP 状态码。</li>
 *   <li>{@code code=500} 一律判红（那是服务故障，不是"被拒绝"），避免把故障读成"安全拒绝"。</li>
 * </ul>
 *
 * <p>参数（示例）：
 * <ul>
 *   <li>{@code evidence.baseUrl.gateway}：<b>必需</b>。用户侧路径一律经网关 ——
 *       下游服务的 {@code HeaderAuthenticationFilter} 只认网关注入的
 *       {@code X-User-Id}/{@code X-User-Name}（且需内部签名），<b>不认</b> {@code Authorization: Bearer}，
 *       直连服务 + Bearer 会 401。</li>
 *   <li>{@code evidence.baseUrl.notification.internal}：<b>必需</b>（仅内部契约用例）。直连 notification；
 *       网关无 {@code /internal/**} 路由（404），用网关地址会把"网关不暴露内部路径"误判成"内部契约坏了"。</li>
 *   <li>{@code evidence.s4.jwt}：具备 notification:history/logs 与 system:audit:list 的 Bearer token（脱敏传入）</li>
 *   <li>{@code evidence.s4.userId.self} / {@code evidence.s4.userId.other}</li>
 *   <li>{@code cloud-infra.internal-call.secret} 或 env {@code INTERNAL_CALL_SECRET}（内部契约用例需要）</li>
 *   <li>{@code evidence.s4.requireNotificationSource=true}：要求审计时间线含 {@code source=NOTIFICATION}</li>
 *   <li>{@code evidence.s4.jwt.tenantB} / {@code evidence.s4.userId.tenantA} /
 *       {@code evidence.s4.tenantIsolation.expectCode}：租户隔离用例（可选，缺则整例跳过）。
 *       未给 {@code expectCode} 时断言**严格隔离**（被拒绝或查不到）——这是本用例的默认期望；
 *       若产品口径是"平台级审计允许超级管理员跨租户查看"，用 {@code expectCode=200} 显式固化当前契约。</li>
 * </ul>
 */
class NotificationSystemB3EvidenceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void b3_notification_selfAndCrossUser_contract() throws Exception {
        assumeEnabled();
        // 用户侧路径必须经网关: 下游服务的 HeaderAuthenticationFilter 只认
        // 网关注入的 X-User-Id/X-User-Name (且需内部签名), **不认** Authorization Bearer。
        String notifBase = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String jwt = System.getProperty("evidence.s4.jwt");
        String self = System.getProperty("evidence.s4.userId.self");
        String other = System.getProperty("evidence.s4.userId.other");
        assumeThat(notifBase).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(jwt).as("evidence.s4.jwt required").isNotBlank();
        assumeThat(self).as("evidence.s4.userId.self required").isNotBlank();
        assumeThat(other).as("evidence.s4.userId.other required").isNotBlank();

        // 自助端点：本人可见
        assertResultSuccess(getAuthed(notifBase + "/notification/my/history", jwt), "/notification/my/history");
        assertResultSuccess(getAuthed(notifBase + "/notification/my/logs", jwt), "/notification/my/logs");

        // legacy 端点：查本人允许
        assertResultSuccess(getAuthed(notifBase + "/notification/history?userId=" + enc(self), jwt), "legacy history self");
        assertResultSuccess(getAuthed(notifBase + "/notification/logs?userId=" + enc(self), jwt), "legacy logs self");

        // legacy 端点：查他人 -> Result.fail(403) + 引导 /my/*，且不得返回成功数据
        HttpResponse<String> crossHistory = getAuthed(notifBase + "/notification/history?userId=" + enc(other), jwt);
        assertResultDenied(crossHistory, "legacy history cross-user", 403);
        assertThat(body(crossHistory).path("message").asText(""))
                .as("跨用户查询应引导到 /notification/my/history, body=%s", crossHistory.body())
                .contains("/notification/my/history");
        assertNoSuccessPayload(crossHistory, "legacy history cross-user");

        HttpResponse<String> crossLogs = getAuthed(notifBase + "/notification/logs?userId=" + enc(other), jwt);
        assertResultDenied(crossLogs, "legacy logs cross-user", 403);
        assertThat(body(crossLogs).path("message").asText(""))
                .as("跨用户查询应引导到 /notification/my/logs, body=%s", crossLogs.body())
                .contains("/notification/my/logs");
        assertNoSuccessPayload(crossLogs, "legacy logs cross-user");
    }

    @Test
    void internal_notification_logs_hmac_contract() throws Exception {
        assumeEnabled();
        // 内部契约必须**直连** notification: 网关没有 /internal/** 路由(返回 404),
        // 用网关地址会把"网关不暴露内部路径"误判成"内部契约坏了"。
        String internalBase = trimEnd(System.getProperty("evidence.baseUrl.notification.internal"));
        String userId = firstNonBlank(System.getProperty("evidence.s4.userId.self"),
                System.getProperty("evidence.internal.userId"));
        String secret = firstNonBlank(System.getProperty("cloud-infra.internal-call.secret"),
                System.getenv("INTERNAL_CALL_SECRET"));
        assumeThat(internalBase).as("evidence.baseUrl.notification.internal required (直连 notification, 非网关)").isNotBlank();
        assumeThat(userId).as("evidence.s4.userId.self or evidence.internal.userId required").isNotBlank();
        assumeThat(secret).as("internal call secret required").isNotBlank();

        // 有效 HMAC -> 成功
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String sign = InternalCallCrypto.sign(secret, ts, nonce);
        HttpRequest signed = HttpRequest.newBuilder()
                .uri(URI.create(internalBase + "/internal/notification/logs?userId=" + enc(userId)))
                .timeout(Duration.ofSeconds(10))
                .header(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts))
                .header(InternalCallCrypto.HEADER_NONCE, nonce)
                .header(InternalCallCrypto.HEADER_SIGN, sign)
                .GET()
                .build();
        HttpResponse<String> ok = CLIENT.send(signed, HttpResponse.BodyHandlers.ofString());
        assertResultSuccess(ok, "internal logs signed");

        // 无签名 -> 拒绝（匿名 -> AuthenticationEntryPoint -> Result.fail(401)），且不得泄露数据
        HttpResponse<String> unsigned = get(internalBase + "/internal/notification/logs?userId=" + enc(userId));
        assertResultDenied(unsigned, "internal logs unsigned", 401, 403);
        assertNoSuccessPayload(unsigned, "internal logs unsigned");
    }

    @Test
    void system_audit_timeline_aggregates_notification_when_configured() throws Exception {
        assumeEnabled();
        String systemBase = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String jwt = System.getProperty("evidence.s4.jwt");
        String self = System.getProperty("evidence.s4.userId.self");
        assumeThat(systemBase).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(jwt).as("evidence.s4.jwt required").isNotBlank();
        assumeThat(self).as("evidence.s4.userId.self required").isNotBlank();

        HttpResponse<String> resp = getAuthed(systemBase + "/system/audit/timeline?userId=" + enc(self), jwt);
        assertThat(resp.statusCode())
                .as("audit timeline status=%s body=%s", resp.statusCode(), resp.body())
                .isEqualTo(200);
        // AuditController 返回 List<UnifiedAuditRecord>，但**出网关后被 Result 包装**:
        // 实测 body = {"code":200,"data":[...],"message":"操作成功","success":true}。
        // 所以这里两种形状都要认: 直接数组(直连) 或 Result.data 数组(经网关)。
        JsonNode root = body(resp);
        JsonNode timeline = root.isArray() ? root : root.get("data");
        assertThat(timeline != null && timeline.isArray())
                .as("audit timeline should be a JSON array (raw or Result.data), body=%s", resp.body())
                .isTrue();

        if (Boolean.parseBoolean(System.getProperty("evidence.s4.requireNotificationSource", "false"))) {
            boolean hasNotification = false;
            for (JsonNode n : timeline) {
                if (n.hasNonNull("source") && "NOTIFICATION".equals(n.get("source").asText())) {
                    hasNotification = true;
                    break;
                }
            }
            assertThat(hasNotification)
                    .as("expected NOTIFICATION source in audit timeline (env must have notification logs for user). body=%s",
                            resp.body())
                    .isTrue();
        }
    }

    @Test
    void system_audit_timeline_tenantIsolation_when_configured() throws Exception {
        assumeEnabled();
        String systemBase = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String jwtB = System.getProperty("evidence.s4.jwt.tenantB");
        String userA = System.getProperty("evidence.s4.userId.tenantA");
        assumeThat(systemBase).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(jwtB).as("evidence.s4.jwt.tenantB required for tenant isolation check").isNotBlank();
        assumeThat(userA).as("evidence.s4.userId.tenantA required for tenant isolation check").isNotBlank();

        HttpResponse<String> resp = getAuthed(systemBase + "/system/audit/timeline?userId=" + enc(userA), jwtB);
        String expect = System.getProperty("evidence.s4.tenantIsolation.expectCode");
        if (expect != null && !expect.isBlank()) {
            // 显式期望: 按实现口径, "被拒绝"是 HTTP 200 + body.code=401/403,
            // 所以比对**响应体 code**(同时接受 HTTP 状态码作为等价表达)。
            int expected = Integer.parseInt(expect.trim());
            int bodyCode = body(resp).path("code").asInt(-1);
            assertThat(bodyCode == expected || resp.statusCode() == expected)
                    .as("tenant isolation expected %s (http=%s bodyCode=%s body=%s)",
                            expected, resp.statusCode(), bodyCode, resp.body())
                    .isTrue();
        } else {
            int bodyCode = body(resp).path("code").asInt(-1);
            boolean denied = bodyCode == 401 || bodyCode == 403
                    || resp.statusCode() == 401 || resp.statusCode() == 403;
            JsonNode payload = body(resp).isArray() ? body(resp) : body(resp).get("data");
            boolean empty = payload != null && payload.isArray() && payload.isEmpty();
            assertThat(denied || empty)
                    .as("tenant B must not read tenant A timeline (http=%s bodyCode=%s body=%s)",
                            resp.statusCode(), bodyCode, resp.body())
                    .isTrue();
            // 严格模式下还要确认**没有把 tenant A 的数据带出来**
            assertNoSuccessPayload(resp, "tenant isolation");
        }
        // 注: 显式给了 expectCode(=固化当前契约)时不再断言"无载荷" ——
        // 那正是该模式下要记录的事实, 两者同时断言会自相矛盾。
    }

    private static void assumeEnabled() {
        assumeThat(System.getProperty("evidence.it")).as("evidence.it=true required").isEqualTo("true");
    }

    /**
     * 请求超时 60s + 一次重试。
     *
     * <p>为什么不是 15s: 实测第一次打 {@code /notification/my/history} 在冷启动
     * （服务刚 healthy、权限缓存/SkyWalking agent 还在初始化）时会超过 15s，
     * 表现为 HttpTimeoutException —— 那是**环境冷启动**，不是被测行为。
     * 重试只针对超时（连接失败/断言失败一律不重试），断言仍作用于真实响应。</p>
     */
    private static HttpResponse<String> get(String url) throws Exception {
        return sendWithTimeoutRetry(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build());
    }

    private static HttpResponse<String> getAuthed(String url, String jwt) throws Exception {
        return sendWithTimeoutRetry(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + jwt).GET().build());
    }

    private static HttpResponse<String> sendWithTimeoutRetry(HttpRequest request) throws Exception {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static JsonNode body(HttpResponse<String> resp) {
        try {
            if (resp.body() == null || resp.body().isBlank()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** 成功：HTTP 2xx 且（success=true 或 code=200）。 */
    private static void assertResultSuccess(HttpResponse<String> resp, String ctx) {
        JsonNode root = body(resp);
        boolean ok = (root.has("success") && root.get("success").asBoolean())
                || (root.has("code") && root.get("code").asInt(-1) == 200);
        assertThat(resp.statusCode() >= 200 && resp.statusCode() < 300)
                .as("%s expected HTTP 2xx, status=%s body=%s", ctx, resp.statusCode(), resp.body())
                .isTrue();
        assertThat(ok)
                .as("%s expected success result (code=200/success=true), body=%s", ctx, resp.body())
                .isTrue();
    }

    /**
     * 被拒绝：HTTP 401/403，或响应体 {@code Result.code} 落在允许集合内。
     * {@code code=500} 明确判红 —— 那是服务故障，不能读成"安全拒绝"。
     */
    private static void assertResultDenied(HttpResponse<String> resp, String ctx, int... allowedCodes) {
        JsonNode root = body(resp);
        int bodyCode = root.path("code").asInt(-1);
        int http = resp.statusCode();

        assertThat(bodyCode)
                .as("%s returned service error code (not a denial): http=%s body=%s", ctx, http, resp.body())
                .isNotEqualTo(500);

        boolean denied = http == 401 || http == 403;
        if (!denied) {
            for (int c : allowedCodes) {
                if (bodyCode == c) {
                    denied = true;
                    break;
                }
            }
        }
        assertThat(denied)
                .as("%s expected denial (http in {401,403} or body code in %s), http=%s bodyCode=%s body=%s",
                        ctx, java.util.Arrays.toString(allowedCodes), http, bodyCode, resp.body())
                .isTrue();
    }

    /** 不得返回成功数据（success=true 且带非空 data）。 */
    private static void assertNoSuccessPayload(HttpResponse<String> resp, String ctx) {
        JsonNode root = body(resp);
        boolean successTrue = root.has("success") && root.get("success").asBoolean();
        JsonNode data = root.get("data");
        boolean hasData = data != null && !data.isNull()
                && !(data.isArray() && data.isEmpty())
                && !(data.isObject() && data.isEmpty());
        assertThat(successTrue && hasData)
                .as("%s must not return success payload, body=%s", ctx, resp.body())
                .isFalse();
    }

    private static String trimEnd(String s) {
        if (s == null) {
            return null;
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
