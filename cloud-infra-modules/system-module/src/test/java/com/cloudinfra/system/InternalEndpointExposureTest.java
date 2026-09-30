package com.cloudinfra.system;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.system.controller.InternalUserController;
import com.cloudinfra.system.controller.SysUserController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护内部服务间契约端点不会重新暴露到终端用户。
 *
 * <p><b>2026-09-30 P0 背景</b>: {@code UserApi} 的 6 个端点（含返回 BCrypt 密码哈希的
 * {@code /system/user/credential/username/{username}}）此前挂在 {@code /system/user/**} 下。
 * 网关把 {@code /system/**} 路由到 system-module，并且<b>为每个转发请求都注入合法的内部
 * HMAC 签名</b>；而 {@code SystemSecurityConfig} 用
 * {@code requestMatchers(internalCallMatcher(...)).permitAll()} 保护这些路径。于是
 * "签名有效即放行"对网关流量恒成立 —— 任意持有合法 JWT 的用户都能读到他人密码哈希。
 * 第二重缺陷: 该 matcher 在签名无效时返回 {@code false}, 请求会落到
 * {@code anyRequest().authenticated()} 而非被拒。</p>
 *
 * <p>修复是双保险: (1) 端点迁到 {@code /internal/**}, 网关无该路由;
 * (2) {@code /internal/**} 改为单点鉴权决策, 签名无效即拒。
 * 本测试锁住第 (1) 条 —— 纯结构性断言, 不依赖容器, 每次 {@code mvn test} 都跑。</p>
 */
class InternalEndpointExposureTest {

    private static final String INTERNAL_PREFIX = "/internal/";

    @Test
    @DisplayName("UserApi 全部端点必须挂在 /internal/ 前缀下")
    void userApiIsFullyInternal() {
        Set<String> paths = collectPaths(UserApi.class);
        assertThat(paths).isNotEmpty();
        assertThat(paths)
            .as("UserApi 是纯服务间契约, 任何端点回到 /system/** 都会重新暴露给终端用户")
            .allMatch(p -> p.startsWith(INTERNAL_PREFIX));
    }

    @Test
    @DisplayName("内部契约端点必须由 InternalUserController 承载, 且带 /internal/ 前缀")
    void internalControllerIsUnderInternalPrefix() {
        assertThat(collectPaths(InternalUserController.class))
            .allMatch(p -> p.startsWith("/internal/system/user"));
    }

    @Test
    @DisplayName("公开用户控制器不得再出现凭据/安全上下文/权限查询端点")
    void publicUserControllerExposesNoCredentials() {
        Set<String> paths = collectPaths(SysUserController.class);
        assertThat(paths)
            .as("SysUserController 只应保留管理端 CRUD, 凭据类端点必须留在 InternalUserController")
            .noneMatch(p -> p.contains("credential")
                || p.contains("/detail/")
                || p.contains("/permissions/")
                || p.contains("/register"));
    }

    @Test
    @DisplayName("凭据类端点仍然存在且只在内部控制器上（防止误删导致认证链路断裂）")
    void credentialEndpointsStillExistInternally() {
        assertThat(collectPaths(InternalUserController.class))
            .contains("/internal/system/user/credential/{userId}",
                      "/internal/system/user/credential/username/{username}",
                      "/internal/system/user/detail/{userId}",
                      "/internal/system/user/register");
    }

    /** 收集类上 {@code @RequestMapping} 前缀 + 方法上映射路径的全量路径 */
    private static Set<String> collectPaths(Class<?> type) {
        RequestMapping classMapping =
            AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String prefix = firstNonBlank(classMapping == null ? null : classMapping.path(),
                                      classMapping == null ? null : classMapping.value());

        Set<String> paths = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            // @GetMapping 等是 @RequestMapping 的元注解, findMergedAnnotation 会解出合并结果
            RequestMapping methodMapping =
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (methodMapping == null) {
                continue;
            }
            String path = firstNonBlank(methodMapping.path(), methodMapping.value());
            if (!path.isEmpty()) {
                paths.add(normalize(prefix + path));
            }
        }
        return paths;
    }

    private static String firstNonBlank(String[]... candidates) {
        for (String[] group : candidates) {
            if (group == null) {
                continue;
            }
            for (String candidate : group) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private static String normalize(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
