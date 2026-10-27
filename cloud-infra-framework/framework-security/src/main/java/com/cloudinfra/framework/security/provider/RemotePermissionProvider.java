package com.cloudinfra.framework.security.provider;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.security.IPermissionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnClass(UserApi.class)
public class RemotePermissionProvider implements IPermissionProvider {

    private static final Logger log = LoggerFactory.getLogger(RemotePermissionProvider.class);

    private final UserApi userApi;

    public RemotePermissionProvider(UserApi userApi) {
        this.userApi = userApi;
    }

    @Override
    // A3 关键修复: 暂时禁用 @Cacheable.
    // 原 @Cacheable + Spring Cache + CaffeineCacheManager 缓存 @Cacheable 失败/空结果,
    // 导致 5min TTL 内业务持续 401 无法恢复.
    // 真正修: 移除 @Cacheable, 依赖 HeaderAuthenticationFilter 的 L1 PermissionLocalCache.
    // Feign 调 system 失败时, 抛 PermissionFetchException,
    // HeaderAuthenticationFilter catch 后走 fallback 装配 (空 permissions),
    // 30s 后 L1 失效再重试 Feign.
    public List<String> getPermissionsByUserId(Long userId) {
        try {
            Result<List<String>> result = userApi.getPermissionsByUserId(userId);
            if (result == null) {
                log.warn("getPermissionsByUserId 收到 null result, userId={}", userId);
                throw new PermissionFetchException("Result was null for userId=" + userId);
            }
            if (!result.isSuccess()) {
                log.warn("getPermissionsByUserId 失败, userId={}, msg={}", userId, result.getMessage());
                throw new PermissionFetchException("Result failed for userId=" + userId + ": " + result.getMessage());
            }
            List<String> data = result.getData();
            if (data == null) {
                log.warn("getPermissionsByUserId 收到 null data, userId={}", userId);
                throw new PermissionFetchException("Result data was null for userId=" + userId);
            }
            return data;
        } catch (PermissionFetchException ex) {
            throw ex;
        } catch (Exception ex) {
            // A3 安全边界原则: 本处是**安全判定边界** (AGENTS.md 第四课 教训 2)。
            // 任何 Feign 调用失败(超时/连接拒绝/5xx/未知异常)都应被统一封装为 PermissionFetchException,
            // 让下游安全过滤器退化为"无权限 → 拒绝"。保留宽捕获: 收窄会漏掉部分失败模式,
            // 把"拒绝伪装成服务故障"(500) —— 这比"无权限"更危险。此 catch 目的是**拒绝**, 不是归一化。
            log.warn("getPermissionsByUserId Feign 调用失败, userId={}, err={}", userId, ex.toString());
            throw new PermissionFetchException("Feign call failed for userId=" + userId, ex);
        }
    }

    @Override
    public LoginUser getUserContext(Long userId) {
        try {
            Result<LoginUser> result = userApi.getUserDetail(userId);
            if (result == null || !result.isSuccess() || result.getData() == null) {
                String msg = result == null ? "null" : result.getMessage();
                log.warn("getUserContext 失败, userId={}, msg={}", userId, msg);
                throw new PermissionFetchException("getUserContext failed for userId=" + userId + ": " + msg);
            }
            return result.getData();
        } catch (PermissionFetchException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("getUserContext Feign 调用失败, userId={}, err={}", userId, ex.toString());
            throw new PermissionFetchException("Feign call failed for userId=" + userId, ex);
        }
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = { "permissions", "userContexts" }, key = "#userId")
    public void clearPermissionCache(Long userId) {
        // Cache is cleared via @CacheEvict
    }

    /**
     * 权限获取失败标记, 用于阻断 @Cacheable 缓存空 list/空对象等"假成功"结果。
     * 不抛此异常会导致业务层 30 分钟持续 401 (缓存期间不重试)。
     */
    public static class PermissionFetchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PermissionFetchException(String msg) { super(msg); }
        public PermissionFetchException(String msg, Throwable cause) { super(msg, cause); }
    }
}
