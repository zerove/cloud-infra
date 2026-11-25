package com.cloudinfra.framework.web.interceptor;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.tenant.TenantContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

public class FeignAuthInterceptor implements RequestInterceptor {

    private final String internalCallSecret;

    public FeignAuthInterceptor(String internalCallSecret) {
        this.internalCallSecret = internalCallSecret;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 标记为内部服务间调用：HMAC 签名 + 时间戳 + 随机数
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        template.header(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(internalCallSecret, timestamp, nonce));
        template.header(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(timestamp));
        template.header(InternalCallCrypto.HEADER_NONCE, nonce);

        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String userId = request.getHeader("X-User-Id");
            String username = request.getHeader("X-User-Name");
            if (userId != null) {
                template.header("X-User-Id", userId);
            }
            if (username != null) {
                template.header("X-User-Name", username);
            }

            // issue 36 深化防御: 内部调用 (如审计聚合 → notification getInternalLogs) 也要把
            // 租户透传给下游, 由下游 TenantContextFilter (同一内部签名边界) 注入 TenantContext.
            String tenantId = TenantContext.getTenantIdOrDefault();
            if (tenantId != null) {
                template.header("X-Tenant-Id", tenantId);
            }
        }

        // issue 36 深化防御: 租户头必须**在 Servlet 请求上下文之外**也能透传。
        // 审计聚合等场景在虚拟线程里发起 Feign 调用: TenantContext 由调用方显式
        // 捕获并设置, 但 RequestContextHolder 不会跨线程继承 (Spring 默认
        // threadContextInheritable=false)。若把本段放进上面的 attributes 分支,
        // 异步路径永远发不出 X-Tenant-Id, 下游 @TenantFilter 退化为全租户查询。
        TenantContext.getTenantId().ifPresent(tenantId -> template.header("X-Tenant-Id", tenantId));
    }
}
