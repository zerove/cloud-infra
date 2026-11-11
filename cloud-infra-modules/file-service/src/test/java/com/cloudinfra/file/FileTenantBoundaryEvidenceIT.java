package com.cloudinfra.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * S5 运行时证据（file 路径 + 租户/认证边界）。
 *
 * <p>opt-in：仅在 {@code -Devidence.it=true} 且所需参数齐全时执行真实 HTTP 断言；
 * 缺参通过 {@code assume} 跳过（不伪造通过）。</p>
 *
 * <p>判定口径（按 {@code FileController} 实现读出来，不靠猜）：
 * <ul>
 *   <li>{@code /file/history/{fileId}} 无权限时返回 {@code Result.fail(403, "无权限查看版本历史")}，
 *       即 <b>HTTP 200 + body.code=403</b>（不是 HTTP 403）；</li>
 *   <li>未认证时由 {@code AuthenticationEntryPointImpl} 返回 <b>HTTP 200 + body.code=401</b>；</li>
 *   <li>{@code body.code=500} 一律判红 —— 那是服务故障，不能读成"被拒绝"。</li>
 * </ul>
 *
 * <p>参数（示例）：
 * <ul>
 *   <li>{@code evidence.baseUrl.gateway}：<b>必需</b>。用户侧路径一律经网关 ——
 *       下游服务的 {@code HeaderAuthenticationFilter} 只认网关注入的
 *       {@code X-User-Id}/{@code X-User-Name}（且需内部签名），<b>不认</b> {@code Authorization: Bearer}，
 *       直连 file-service + Bearer 会 401。</li>
 *   <li>{@code evidence.s5.jwt.tenantA}：可访问目标文件的 Bearer token</li>
 *   <li>{@code evidence.s5.fileId.tenantA}：租户 A 归属文件 id</li>
 *   <li>{@code evidence.s5.jwt.tenantB}：租户 B token（跨租户用例）</li>
 *   <li>{@code evidence.s5.crossTenant.expectCode}：跨租户期望的 <b>body.code</b>（如 403）；未提供则要求"被拒绝或查不到"</li>
 *   <li>{@code evidence.s5.search.keyword}：可选，附加验证 {@code /file/search}</li>
 *   <li>{@code evidence.s5.jwt.unauthorized}：可选，无 file 权限 token</li>
 * </ul>
 */
class FileTenantBoundaryEvidenceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void tenantA_canAccessOwnFileMetadata_whenConfigured() throws Exception {
        assumeEnabled();
        String base = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String jwtA = System.getProperty("evidence.s5.jwt.tenantA");
        String fileId = System.getProperty("evidence.s5.fileId.tenantA");
        assumeThat(base).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(jwtA).as("evidence.s5.jwt.tenantA required").isNotBlank();
        assumeThat(fileId).as("evidence.s5.fileId.tenantA required").isNotBlank();

        // 低风险读路径：版本历史（Result 包装）
        HttpResponse<String> history = getAuthed(base + "/file/history/" + fileId, jwtA);
        assertResultSuccess(history, "file history tenantA");

        // 搜索端点（需 file:file:search 权限）
        String keyword = System.getProperty("evidence.s5.search.keyword");
        if (keyword != null && !keyword.isBlank()) {
            HttpResponse<String> search = getAuthed(base + "/file/search?keyword=" + enc(keyword), jwtA);
            assertResultSuccess(search, "file search tenantA");
        }
    }

    @Test
    void tenantB_cannotAccessTenantAFile_whenConfigured() throws Exception {
        assumeEnabled();
        String base = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String jwtB = System.getProperty("evidence.s5.jwt.tenantB");
        String fileId = System.getProperty("evidence.s5.fileId.tenantA");
        assumeThat(base).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(jwtB).as("evidence.s5.jwt.tenantB required").isNotBlank();
        assumeThat(fileId).as("evidence.s5.fileId.tenantA required").isNotBlank();

        HttpResponse<String> resp = getAuthed(base + "/file/history/" + fileId, jwtB);
        String expect = System.getProperty("evidence.s5.crossTenant.expectCode");
        if (expect != null && !expect.isBlank()) {
            int expected = Integer.parseInt(expect.trim());
            int bodyCode = body(resp).path("code").asInt(-1);
            assertThat(bodyCode == expected || resp.statusCode() == expected)
                    .as("cross-tenant expected %s (http=%s bodyCode=%s body=%s)",
                            expected, resp.statusCode(), bodyCode, resp.body())
                    .isTrue();
        } else {
            // 安全属性是"跨租户读不到对方文件", 不强绑具体状态码:
            // 被拒绝(401/403/404) 或 查询被租户过滤成空结果 都算通过,
            // 但 **不能** 是"带着对方文件数据返回成功"。
            int bodyCode = body(resp).path("code").asInt(-1);
            boolean denied = bodyCode == 401 || bodyCode == 403 || bodyCode == 404
                    || resp.statusCode() == 401 || resp.statusCode() == 403 || resp.statusCode() == 404;
            boolean emptySuccess = body(resp).path("success").asBoolean(false)
                    && isEmptyPayload(body(resp).get("data"));
            assertThat(denied || emptySuccess)
                    .as("cross-tenant must be denied or empty (http=%s bodyCode=%s body=%s)",
                            resp.statusCode(), bodyCode, resp.body())
                    .isTrue();
        }
        assertNoFileDataLeak(resp, "cross-tenant file history");
    }

    @Test
    void unauthenticated_or_unauthorized_fileAccess_isRejected_whenConfigured() throws Exception {
        assumeEnabled();
        String base = trimEnd(System.getProperty("evidence.baseUrl.gateway"));
        String fileId = System.getProperty("evidence.s5.fileId.tenantA");
        assumeThat(base).as("evidence.baseUrl.gateway required").isNotBlank();
        assumeThat(fileId).as("evidence.s5.fileId.tenantA required").isNotBlank();

        // 未认证
        HttpResponse<String> noAuth = get(base + "/file/history/" + fileId);
        assertResultDenied(noAuth, "file history unauthenticated", 401, 403);
        assertNoFileDataLeak(noAuth, "file history unauthenticated");

        // 可选：无权限 token
        String jwtNoPerm = System.getProperty("evidence.s5.jwt.unauthorized");
        if (jwtNoPerm != null && !jwtNoPerm.isBlank()) {
            HttpResponse<String> noPerm = getAuthed(base + "/file/history/" + fileId, jwtNoPerm);
            assertResultDenied(noPerm, "file history unauthorized token", 401, 403);
            assertNoFileDataLeak(noPerm, "file history unauthorized token");
        }
    }

    private static void assumeEnabled() {
        assumeThat(System.getProperty("evidence.it")).as("evidence.it=true required").isEqualTo("true");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getAuthed(String url, String jwt) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
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

    /** 被拒绝：HTTP 401/403 或 body.code 落在允许集合；code=500 明确判红。 */
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

    /** 不得回传文件数据（fileId / storagePath / versionNumber 等）。 */
    private static void assertNoFileDataLeak(HttpResponse<String> resp, String ctx) {
        String b = resp.body();
        if (b == null) {
            return;
        }
        boolean leaked = b.contains("\"fileId\"") || b.contains("\"storagePath\"") || b.contains("\"versionNumber\"");
        assertThat(leaked)
                .as("%s must not leak file data, body=%s", ctx, b)
                .isFalse();
    }

    private static boolean isEmptyPayload(JsonNode data) {
        if (data == null || data.isNull()) {
            return true;
        }
        if (data.isArray()) {
            return data.isEmpty();
        }
        if (data.isObject()) {
            return data.isEmpty();
        }
        return false;
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
