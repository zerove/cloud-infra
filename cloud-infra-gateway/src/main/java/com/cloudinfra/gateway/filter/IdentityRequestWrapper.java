package com.cloudinfra.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IdentityRequestWrapper extends HttpServletRequestWrapper {
    private final Map<String, String> customHeaders;

    public IdentityRequestWrapper(HttpServletRequest request, Map<String, String> headers) {
        super(request);
        this.customHeaders = new HashMap<>(headers);
    }

    /**
     * {@code customHeaders} 中<b>值为 {@code null} 的键表示"显式剥离"</b>:
     * 读它时返回 {@code null} 而<b>不</b>回退到原始请求头。
     *
     * <p>为什么需要区分"没设置"与"显式剥离": 回退到 {@code super.getHeader} 意味着
     * 客户端自带的同名头会漏过去。网关对租户头正是要防这个 ——
     * 白名单路径（无登录主体）下若不剥离, 客户端的 {@code X-Tenant-Id} 会原样到达下游,
     * 下游 {@code TenantContextFilter} 验签失败 → {@code /auth/login} 这类免认证端点被 403 误伤。</p>
     */
    @Override
    public String getHeader(String name) {
        if (customHeaders.containsKey(name)) {
            return customHeaders.get(name);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (customHeaders.containsKey(name)) {
            String value = customHeaders.get(name);
            return value == null
                ? Collections.emptyEnumeration()
                : Collections.enumeration(List.of(value));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        // 剥离的键不再出现在头名列表里, 避免下游遍历头时又看到它
        customHeaders.forEach((name, value) -> {
            if (value != null) {
                names.add(name);
            }
        });
        return Collections.enumeration(names);
    }
}
