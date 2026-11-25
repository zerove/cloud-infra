package com.cloudinfra.notification.ws;

import com.cloudinfra.common.core.crypto.InternalCallVerifier;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * WebSocket 鉴权 Servlet Filter (方案 C: 拦截 Upgrade 握手请求).
 *
 * <p><b>设计动机</b>: 原 @ServerEndpoint("/notification/ws/{userId}") 模式从 URL 提取 userId,
 * 客户端可伪造 ws://host/ws/任意ID 冒充他人. M7-03 改造要求 userId 必须来自可信 Header
 * (经 Gateway 验签后注入的 X-User-Id).</p>
 *
 * <p><b>为何选方案 C 而非 A/B</b>:
 * <ul>
 *   <li>方案 A 需 jakarta.websocket-api 2.3+ 暴露 HandshakeResponse, 当前镜像无该版本</li>
 *   <li>方案 B (Spring HandshakeInterceptor) 仅对 spring WebSocketHandler 生效, 不可用于 @ServerEndpoint</li>
 *   <li>方案 C 通过 Servlet Filter 在握手请求到达 WS 端点前拦截, 写入 HttpSession 属性,
 *       Configurator 再从 session 读取并注入 userProperties (本 Filter 不依赖 API 升级)</li>
 * </ul>
 *
 * <p><b>工作流程</b>:
 * <ol>
 *   <li>客户端发起 GET /notification/ws 请求, Header 携带 X-User-Id (来自 Gateway)</li>
 *   <li>Servlet Filter 在 WebSocket Upgrade 之前执行, 校验 X-User-Id</li>
 *   <li>校验通过 → 将 userId 写入 request.getSession().setAttribute(AUTH_USER_ID_KEY, userId),
 *       WebSocketAuthConfigurator 从 session 读取并注入 session.getUserProperties()</li>
 *   <li>校验失败 → 直接返回 401, 拒绝升级握手</li>
 * </ol>
 *
 * <p><b>身份来源校验 (2026-09-29 补齐)</b>: 原实现只校验 {@code X-User-Id} 是否存在,
 * 不校验其是否真由网关注入。实测 (WebSocketIdentityTrustIT) 确认: 任何能直连本服务端口的
 * 一方, 仅凭 {@code X-User-Id: <任意 ID>} 即可冒充该用户订阅其实时通知。
 * 现与 HTTP 侧对齐 —— 网关 SecurityFilter 会为所有经其转发的请求注入
 * 内部调用 HMAC 签名, 此处在 {@code verify-internal-sign=true} 时要求验签通过。
 * 开关与 HTTP 侧同开关同默认值 (fail-closed 默认 true); 本地直连调试可显式置 false。</p>
 *
 * <p>本 Filter 仅作用于 WebSocket 握手请求 (匹配 WS_PATH 前缀), 不影响其他 HTTP 端点.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebSocketAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_ID_KEY = "auth.userId";
    public static final String AUTH_USER_NAME_KEY = "auth.userName";
    public static final String AUTH_TENANT_ID_KEY = "auth.tenantId";

    public static final String WS_PATH_PREFIX = "/notification/ws";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 是否要求内部调用签名。与 HTTP 侧 {@code HeaderAuthenticationFilter}
     * 使用同一开关名, 两处默认值一致 (fail-closed 默认 true)。
     * 本地直连调试确需关闭时显式置 false。
     */
    @Value("${cloud-infra.security.verify-internal-sign:true}")
    private boolean verifyInternalSign;

    /** 可选注入: 单元测试以无参构造创建本 Filter, 此时无密钥提供者。 */
    @Autowired(required = false)
    private InternalCallSecretProvider secretProvider;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅过滤 WebSocket 端点
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(WS_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 仅在 WebSocket 握手阶段校验 (必须含 Upgrade 头)
        String upgrade = request.getHeader(HttpHeaders.UPGRADE);
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            // 非握手请求 (例如浏览器探测 / 静态资源) 直接放行
            chain.doFilter(request, response);
            return;
        }

        if (!isTrustedGatewayRequest(request)) {
            log.warn("WebSocket 握手拒绝: 内部调用签名无效, remoteAddr={}",
                request.getRemoteAddr());
            writeUnauthorized(response, "internal call signature invalid");
            return;
        }

        String userId = request.getHeader("X-User-Id");
        String userName = request.getHeader("X-User-Name");
        String tenantId = request.getHeader("X-Tenant-Id");

        if (userId == null || userId.isBlank()) {
            log.warn("WebSocket 握手拒绝: 缺少 X-User-Id header, remoteAddr={}",
                request.getRemoteAddr());
            writeUnauthorized(response, "missing X-User-Id header");
            return;
        }

        // 写入 HttpSession, Configurator 从 session 读取
        var session = request.getSession(true);
        session.setAttribute(AUTH_USER_ID_KEY, userId);
        if (userName != null) {
            session.setAttribute(AUTH_USER_NAME_KEY, userName);
        }
        if (tenantId != null) {
            session.setAttribute(AUTH_TENANT_ID_KEY, tenantId);
        }

        log.debug("WebSocket 握手鉴权通过: userId={}, tenantId={}, 验签={}",
            userId, tenantId, verifyInternalSign);
        chain.doFilter(request, response);
    }

    /**
     * 判断请求是否来自可信网关。
     *
     * <p>关闭验签时恒为可信 (兼容存量直连调试); 开启时要求
     * {@code X-Internal-Call} / {@code -Timestamp} / {@code -Nonce} 三头签名有效。
     * 判定逻辑统一在 {@link InternalCallVerifier} (单一真源); 密钥缺失时按 fail-closed
     * 拒绝而非放行。本站点允许无容器场景 (secretProvider 为 null → 密钥按缺失处理)。
     */
    private boolean isTrustedGatewayRequest(HttpServletRequest request) {
        String secret = secretProvider != null ? secretProvider.getSecret() : null;
        return InternalCallVerifier.isTrusted(request, verifyInternalSign, secret);
    }

    private static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(Map.of(
            "code", 401,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }
}
