package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 业务告警指标 AOP 切面 (M5-03, 2026-09-01)
 *
 * <p>对标注了 {@link BusinessMetric} 注解的方法,在异常时自动 increment 对应 Counter。
 * 避免在每个业务方法里写 try-catch (合规 adg java-catch-all 规则)。
 *
 * <p>使用示例:
 * <pre>
 * {@code
 * @BusinessMetric(metric = "file.upload.failures", reason = "upload")
 * public FileMetadataVO upload(...) { ... }
 * }
 * </pre>
 *
 * <p>对应 Prometheus 告警规则:
 * <ul>
 *   <li>AuthLoginFailureHigh — auth.*.* login 抛异常时 increment auth.login.attempts{result=failure}</li>
 *   <li>FileUploadFailureHigh — file.*.* upload 抛异常时 increment file.upload.failures{reason=upload}</li>
 * </ul>
 *
 * <p>设计要点:
 * <ul>
 *   <li>按 metric + reason 缓存 Counter,避免每次 increment 都 register (Micrometer 内部会缓存,但显式更清晰)</li>
 *   <li>原始异常继续向上抛,业务逻辑不变</li>
 *   <li>仅统计异常情况,正常返回不触发</li>
 * </ul>
 */
@Aspect
@Component
public class BusinessMetricAspect {
    private static final Logger log = LoggerFactory.getLogger(BusinessMetricAspect.class);

    private final MeterRegistry registry;

    /**
     * Counter 缓存。
     *
     * <p>必须是 {@link ConcurrentHashMap}: 本切面拦截的是**异常路径**
     * (以及带 resultOnSuccess 的返回路径), 高并发下多个请求会同时进入
     * {@link #increment}。用普通 {@code HashMap} 时:
     * <ul>
     *   <li>并发 {@code computeIfAbsent} 可能抛 {@code ConcurrentModificationException}</li>
     *   <li>即便不抛, resize 期间的写也可能丢失 —— 即指标被静默少计</li>
     * </ul>
     * 第二种后果更隐蔽: 不报错, 但告警规则的分子偏小, 可能漏报。
     *
     * <p>{@code computeIfAbsent} 在 ConcurrentHashMap 上对同一 key 保证只执行一次
     * mappingFunction 并返回同一实例, 正是这里需要的语义。
     */
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    @Autowired
    public BusinessMetricAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 成功/失败分桶 (使用 @Around, 不需要 catch 块)
     *
     * <p>行为:
     * <ul>
     *   <li>proceed() 正常返回 → increment success counter</li>
     *   <li>proceed() 抛异常 → increment failure counter, 然后 re-throw 异常 (AOP 自动)</li>
     * </ul>
     *
     * <p>关键: 不写 try-catch, 让 AOP 框架自动处理异常传播
     * (合规 adg java-catch-all / java-catch-throwable 规则)
     */
    @Around("@annotation(businessMetric)")
    public Object track(ProceedingJoinPoint pjp, BusinessMetric businessMetric) throws Throwable {
        boolean trackSuccess = !businessMetric.resultOnSuccess().isEmpty();
        Object result = pjp.proceed();
        if (trackSuccess) {
            String resultTag = resolveResultTag(result, businessMetric);
            increment(businessMetric.metric(), "result", resultTag);
        }
        return result;
    }

    /**
     * 根据返回值判断 success / failure, 优先看 {@link com.cloudinfra.common.core.domain.Result#isSuccess()}
     * (项目统一返回包装), fallback 到 resultOnSuccess 注解值
     */
    private String resolveResultTag(Object result, BusinessMetric businessMetric) {
        if (result instanceof com.cloudinfra.common.core.domain.Result<?> r) {
            return r.isSuccess() ? businessMetric.resultOnSuccess() : businessMetric.resultOnFailure();
        }
        return businessMetric.resultOnSuccess();
    }

    /**
     * 异常切面 (使用 @AfterThrowing 避免 catch 块)
     */
    @AfterThrowing(
        pointcut = "@annotation(businessMetric)",
        throwing = "ex"
    )
    public void trackFailure(JoinPoint joinPoint, BusinessMetric businessMetric, Throwable ex) {
        if (!businessMetric.resultOnSuccess().isEmpty()) {
            increment(businessMetric.metric(), "result", businessMetric.resultOnFailure());
        } else {
            increment(businessMetric.metric(), "reason", businessMetric.reason());
        }
    }

    private void increment(String metric, String tagKey, String tagValue) {
        String key = metric + "|" + tagKey + "|" + tagValue;
        Counter counter = counterCache.computeIfAbsent(key, k ->
            Counter.builder(metric)
                .description("业务方法异常计数器 (M5-03, AfterThrowing 实现)")
                .tag(tagKey, tagValue)
                .register(registry)
        );
        counter.increment();
    }
}
