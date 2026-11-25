package com.cloudinfra.framework.web.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinfra.common.core.tenant.TenantContext;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * issue 36 深化防御: 租户头必须也能在虚拟线程（无 Servlet 请求上下文）的场景透传。
 *
 * <p>审计聚合在虚拟线程里发起内部调用: TenantContext 由调用方显式设置,
 * 但 {@link RequestContextHolder} 不继承 ThreadLocal — 若 Feign 只在
 * attributes != null 分支里发租户头, 异步路径永远发不出, 下游
 * @TenantFilter 在异步路径退化为全租户查询。</p>
 */
class FeignAuthInterceptorTenantTest {

    private final FeignAuthInterceptor interceptor = new FeignAuthInterceptor("test-secret");

    @BeforeEach
    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test
    @DisplayName("无 RequestContextHolder 的虚拟线程仍透传 TenantContext")
    void propagatesTenantWithoutServletRequest() {
        TenantContext.setTenantId("tenant-a");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).containsKey("X-Tenant-Id");
        assertThat(template.headers().get("X-Tenant-Id")).containsExactly("tenant-a");
    }

    @Test
    @DisplayName("无租户上下文时不伪造 X-Tenant-Id")
    void doesNotPropagateWhenNoTenant() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Tenant-Id");
    }
}
