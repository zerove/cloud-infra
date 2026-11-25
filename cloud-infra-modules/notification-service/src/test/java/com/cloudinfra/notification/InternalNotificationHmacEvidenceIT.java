package com.cloudinfra.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Feign+HMAC 内部契约运行时证据（opt-in，非假绿）。
 *
 * <p>仅在显式启用且提供真实环境参数时执行真实 HTTP 调用；否则 {@code assume} 跳过。
 * 本测试不伪造 200/403 结果。</p>
 *
 * <p>启用示例：
 * <pre>
 * mvn -pl cloud-infra-modules/notification-service -am \
 *   -DskipITs=false -Devidence.it=true \
 *   -Devidence.notification.baseUrl=http://127.0.0.1:8084 \
 *   -Devidence.internal.userId=1 \
 *   -Dcloud-infra.internal-call.secret=&lt;secret&gt; \
 *   -Dit.test=InternalNotificationHmacEvidenceIT verify
 * </pre>
 * （命令/插件以项目实际 IT 配置为准；缺少参数时应 skip，不应 pass。）</p>
 */
class InternalNotificationHmacEvidenceIT {

    @Test
    void internalLogs_withValidHmac_isReachableInRealEnvironment() throws Exception {
        assumeThat(System.getProperty("evidence.it")).as("evidence.it=true required").isEqualTo("true");

        String baseUrl = System.getProperty("evidence.notification.baseUrl");
        String userId = System.getProperty("evidence.internal.userId");
        String secret = System.getProperty("cloud-infra.internal-call.secret");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("INTERNAL_CALL_SECRET");
        }

        assumeThat(baseUrl).as("evidence.notification.baseUrl required").isNotBlank();
        assumeThat(userId).as("evidence.internal.userId required").isNotBlank();
        assumeThat(secret).as("internal call secret required (system prop or INTERNAL_CALL_SECRET env)").isNotBlank();

        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String sign = InternalCallCrypto.sign(secret, ts, nonce);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + "/internal/notification/logs?userId=" + userId))
                .timeout(Duration.ofSeconds(10))
                .header(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts))
                .header(InternalCallCrypto.HEADER_NONCE, nonce)
                .header(InternalCallCrypto.HEADER_SIGN, sign)
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("有效 HMAC 调用内部契约应成功（环境/数据问题时返回体: %s）", response.body())
                .isEqualTo(200);
        assertThat(response.body()).as("响应应包含 Result 包装").contains("\"code\"");
    }

    @Test
    void internalLogs_withoutHmac_isRejectedInRealEnvironment() throws Exception {
        assumeThat(System.getProperty("evidence.it")).as("evidence.it=true required").isEqualTo("true");
        String baseUrl = System.getProperty("evidence.notification.baseUrl");
        String userId = System.getProperty("evidence.internal.userId", "1");
        assumeThat(baseUrl).as("evidence.notification.baseUrl required").isNotBlank();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + "/internal/notification/logs?userId=" + userId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        // 框架 AuthenticationEntryPointImpl 可能返回 HTTP 200 + Result{success:false,code:401}。
        // 安全属性是"未授权/无 HMAC 不得返回成功数据"，不绑定 HTTP 状态码约定。
        boolean rejectedByHttp = response.statusCode() == 401 || response.statusCode() == 403;
        boolean rejectedByBody = response.body() != null
                && (response.body().contains("\"success\":false")
                    || response.body().contains("\"code\":401")
                    || response.body().contains("\"code\":403"));
        boolean leakedSuccess = response.body() != null
                && response.body().contains("\"success\":true")
                && response.body().contains("\"data\"");
        assertThat(rejectedByHttp || rejectedByBody)
                .as("无 HMAC 的内部契约调用必须被拒绝且不得泄露数据（status=%s body=%s）",
                        response.statusCode(), response.body())
                .isTrue();
        assertThat(leakedSuccess)
                .as("无 HMAC 调用不得返回成功数据（body=%s）", response.body())
                .isFalse();
    }
}
