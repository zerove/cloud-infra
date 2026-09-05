package com.cloudinfra.framework.mybatis.outbox;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用 Outbox 模式 (M6-03)
 *
 * 业务模块加此注解即可启用 Outbox 事件发布能力, 无需手动注册 Bean.
 * 通过 {@link Import} 触发 {@link OutboxAutoConfiguration} 装配:
 * <ul>
 *   <li>{@link OutboxEventPublisher} (业务 API: publishTransactional)</li>
 *   <li>{@link OutboxEventDispatcher} (异步轮询派发, 含 DLQ)</li>
 *   <li>{@code @PublishOutbox} 注解驱动的 AOP 自动发布能力</li>
 * </ul>
 *
 * 用法:
 * <pre>
 * {@code
 * @SpringBootApplication
 * @EnableOutbox  // <-- 一行启用
 * public class MyApplication { }
 * }
 * </pre>
 *
 * 兼容: 与 {@link com.cloudinfra.starter.PlatformStarterAutoConfiguration}
 * (PlatformStarterProperties.Outbox.enabled) 双轨制, 不会重复注册.
 *
 * 关闭: 设置 {@code cloud-infra.outbox.enabled=false} 或不引入本注解.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(OutboxAutoConfiguration.class)
public @interface EnableOutbox {
}