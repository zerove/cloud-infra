package com.cloudinfra.framework.mybatis.outbox.aspect;

import com.cloudinfra.framework.mybatis.outbox.OutboxEventPublisher;
import com.cloudinfra.framework.mybatis.outbox.PublishOutbox;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * @PublishOutbox 切面 (M6-03)
 *
 * 拦截 @PublishOutbox 注解方法:
 * 1. 执行业务方法, 获取返回值
 * 2. 在事务提交后 (TransactionSynchronizationManager) 自动 publish
 * 3. 序列化失败不阻塞业务返回 (warn log + 跳过)
 *
 * 启用条件: {@link OutboxEventPublisher} Bean 存在时.
 *            (即 @EnableOutbox 或显式 Bean 注册)
 *
 * 非破坏: 业务模块未启用 Outbox 时, 切面不装载.
 */
@Aspect
@Component
public class PublishOutboxAspect {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxAspect.class);

    private final OutboxEventPublisher publisher;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public PublishOutboxAspect(OutboxEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 限定到带 @PublishOutbox 注解的方法, 避免全 bean 拖慢启动.
     */
    @Around("@annotation(publishOutbox)")
    public Object around(ProceedingJoinPoint joinPoint, PublishOutbox publishOutbox) throws Throwable {
        Object result = joinPoint.proceed();

        // 业务异常时 joinPoint.proceed() 已抛出, 不进入 publish 逻辑
        if (result == null) {
            log.debug("[PublishOutboxAspect] 业务返回 null, 跳过 publish: method={}",
                joinPoint.getSignature().toShortString());
            return result;
        }

        try {
            String eventType = resolveEventType(joinPoint, publishOutbox);
            String aggregateType = resolveAggregateType(joinPoint, result, publishOutbox);
            String aggregateId = resolveAggregateId(joinPoint, result, publishOutbox);

            // 在事务内 publish (commit 后才入 outbox_event 表)
            publisher.publishTransactional(
                publishOutbox.bindingName(),
                aggregateType,
                aggregateId,
                eventType,
                result
            );
            log.debug("[PublishOutboxAspect] 发布 Outbox 事件: binding={}, type={}, aggregate={}",
                publishOutbox.bindingName(), eventType, aggregateId);
        } catch (Exception e) {
            // 序列化或 publish 失败不应阻塞业务返回 (业务已成功)
            log.warn("[PublishOutboxAspect] 发布失败, 业务已返回. method={}, err={}",
                joinPoint.getSignature().toShortString(), e.getMessage());
        }

        return result;
    }

    private String resolveEventType(ProceedingJoinPoint joinPoint, PublishOutbox annotation) {
        if (!annotation.eventType().isEmpty()) {
            return annotation.eventType();
        }
        return joinPoint.getSignature().getName();
    }

    private String resolveAggregateType(ProceedingJoinPoint joinPoint, Object result, PublishOutbox annotation) {
        if (!annotation.aggregateType().isEmpty()) {
            return annotation.aggregateType();
        }
        return result.getClass().getSimpleName();
    }

    /**
     * 解析 aggregateId SpEL 表达式. 失败回退 toString().
     */
    private String resolveAggregateId(ProceedingJoinPoint joinPoint, Object result, PublishOutbox annotation) {
        if (annotation.aggregateId().isEmpty()) {
            return String.valueOf(result);
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            EvaluationContext context = buildEvaluationContext(joinPoint, result, method);
            Expression expression = parser.parseExpression(annotation.aggregateId());
            Object value = expression.getValue(context);
            return String.valueOf(value);
        } catch (Exception e) {
            log.warn("[PublishOutboxAspect] aggregateId SpEL 解析失败, 回退 toString: spEL={}, err={}",
                annotation.aggregateId(), e.getMessage());
            return String.valueOf(result);
        }
    }

    private EvaluationContext buildEvaluationContext(ProceedingJoinPoint joinPoint, Object result, Method method) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        // root object: 业务返回值, SpEL 直接访问字段名 (如 userId, id) 无需 #result 前缀
        context.setRootObject(result);
        // 业务方法参数命名 (供 SpEL 通过 #paramName 访问)
        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        // 同时保留 #result 兼容旧用法
        context.setVariable("result", result);
        return context;
    }
}