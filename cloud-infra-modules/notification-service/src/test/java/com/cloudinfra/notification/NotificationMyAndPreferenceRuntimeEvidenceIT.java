package com.cloudinfra.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * S5 运行时证据骨架（通知偏好 / `/my/*` / 配额路径）。
 *
 * <p>本类刻意不启动 Testcontainers 或完整 Spring 上下文：在 `-DskipITs=false` 且具备
 * Postgres/Redis/RabbitMQ/安全上下文时，应替换为真实 IT，验证：
 * <ul>
 *   <li>{@code GET/PUT /notification/preferences} 在真实库与租户过滤下行为正确；</li>
 *   <li>{@code GET /notification/my/history|logs} 不信任客户端 {@code userId}；</li>
 *   <li>{@code GET /notification/quota} 返回值与 TTL/reset 语义符合预期。</li>
 * </ul>
 *
 * <p>完成后移除 {@link Disabled}，并补充触发/端到端证据记录（AGENTS 第三条硬规则）。
 */
@EnabledIfSystemProperty(named = "evidence.it", matches = "true")
class NotificationMyAndPreferenceRuntimeEvidenceIT {

    @Test
    @DisplayName("S5 skeleton - preferences tenant/runtime path")
    void preferencesRuntimeEvidenceSkeleton() {
        // TODO(S5): @SpringBootTest + Testcontainers + 认证头/租户上下文 + 实际请求断言
    }

    @Test
    @DisplayName("S5 skeleton - my history/logs ignore client userId")
    void myEndpointsRuntimeEvidenceSkeleton() {
        // TODO(S5): 验证 /my/history、/my/logs 与旧接口在兼容策略下的运行时行为
    }
}
