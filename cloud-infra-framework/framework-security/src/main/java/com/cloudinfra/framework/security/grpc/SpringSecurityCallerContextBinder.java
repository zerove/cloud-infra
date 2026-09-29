package com.cloudinfra.framework.security.grpc;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.security.CallerContextBinder;
import com.cloudinfra.common.core.security.PlatformCaller;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

/**
 * 调用方身份 → Spring Security 上下文桥接 (M7-02 安全边界).
 *
 * <p>使业务层在 gRPC 调用链内可直接复用既有的 {@code SecurityContextHolder} /
 * {@code PlatformSecurityContext} / {@code SecurityUtils.getUserId()} 判定逻辑,
 * 无需为 gRPC 另写一套鉴权与数据范围过滤。
 *
 * <p>协议框架通过 {@link CallerContextBinder} 反向依赖本实现:
 * {@code framework-grpc} 编译期不引用 spring-security,
 * classpath 上有 {@code framework-security} 时由自动配置注册本实现覆盖 no-op 版本。
 *
 * <p>安全说明: gRPC 入口尚无权限来源, 因此此处只装配身份, 权限集合为空。
 * 这意味着 gRPC 通道内 {@code hasPermi} 一律为 false,
 * 写操作必须先完成方法级权限映射 (M7-02 后续阶段) 才可放开。
 */
public class SpringSecurityCallerContextBinder implements CallerContextBinder {

    @Override
    public void bind(PlatformCaller caller) {
        if (caller == null) {
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                LoginUser.builder()
                    .userId(caller.userId())
                    .username(caller.username())
                    .permissions(Set.of())
                    .build(),
                null,
                List.of()));
    }

    @Override
    public void clear() {
        SecurityContextHolder.clearContext();
    }
}
