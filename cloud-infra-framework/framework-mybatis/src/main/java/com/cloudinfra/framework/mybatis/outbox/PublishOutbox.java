package com.cloudinfra.framework.mybatis.outbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务方法返回值自动发布为 Outbox 事件 (M6-03)
 *
 * 加此注解的业务方法, 返回值会在事务提交后自动序列化入 outbox_event 表,
 * 由 {@link OutboxEventDispatcher} 异步轮询投递.
 *
 * 用法:
 * <pre>
 * {@code
 * @Transactional
 * @PublishOutbox(
 *     bindingName = "auditLog",
 *     eventType = "USER_LOGIN",
 *     aggregateId = "#result.userId"  // SpEL 提取
 * )
 * public LoginResult login(LoginRequest req) {
 *     // 业务逻辑
 *     return new LoginResult(user.getId(), token);
 * }
 * // 事务提交后自动: outbox_event 表插入 (bindingName="auditLog", aggregateId=返回值.userId, payload=JSON(返回值))
 * }
 * </pre>
 *
 * 设计要点:
 * - aggregateId 缺省时使用返回值的 toString() (业务可重写 toString 提供稳定 ID)
 * - aggregateType 缺省时取方法返回类型的简单类名 (如 LoginResult → "LoginResult")
 * - 业务异常时事务回滚, Outbox 不会写入 (与业务数据原子性保证)
 * - payload 序列化失败时不阻塞业务返回 (warn log + 跳过)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublishOutbox {

    /**
     * 目标 binding (必填, 对应消息中间件 channel/exchange)
     */
    String bindingName();

    /**
     * 事件类型 (默认使用方法名)
     */
    String eventType() default "";

    /**
     * 聚合根类型 (默认返回类型的简单类名)
     */
    String aggregateType() default "";

    /**
     * 聚合根 ID SpEL 表达式 (默认使用返回值 toString)
     * 示例: "#result.userId", "#result.id"
     */
    String aggregateId() default "";
}