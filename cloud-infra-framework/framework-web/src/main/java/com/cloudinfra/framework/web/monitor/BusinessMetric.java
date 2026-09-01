package com.cloudinfra.framework.web.monitor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务告警指标注解 (M5-03, 2026-09-01)
 *
 * <p>标注在业务方法上, 由 {@link BusinessMetricAspect} 拦截。
 * 默认仅在方法抛异常时 increment failure counter,
 * 但可通过 resultOnSuccess 启用"成功/失败"分桶 (例: login 成功/失败)。
 *
 * <p>使用示例:
 * <pre>
 * {@code
 * // 仅统计异常 (文件上传失败)
 * @BusinessMetric(metric = "file.upload.failures", reason = "upload")
 * public FileMetadataVO upload(...) { ... }
 *
 * // 统计成功和失败 (登录)
 * @BusinessMetric(metric = "auth.login.attempts", reason = "login",
 *                 resultOnSuccess = "success", resultOnFailure = "failure")
 * public Result<Map<String, Object>> login(...) { ... }
 * }
 * </pre>
 *
 * @see BusinessMetricAspect
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BusinessMetric {
    /**
     * Prometheus 指标名,例如 "file.upload.failures" 或 "auth.login.attempts"
     */
    String metric();

    /**
     * 默认 tag,例如 "upload" / "login"
     */
    String reason() default "unknown";

    /**
     * 启用"成功/失败"分桶, null 表示仅统计异常
     */
    String resultOnSuccess() default "";

    /**
     * 失败时使用的 tag 值, 仅在 resultOnSuccess 非空时生效
     */
    String resultOnFailure() default "failure";
}
