package com.cloudinfra.common.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等性注解 (M6-02)
 *
 * 两种语义模式, 对幂等键的要求不同:
 * <ul>
 *   <li>{@link Mode#REJECT} (默认): 二次调用抛异常, 适用防抖场景 (按钮防双击, 短窗口内拒绝)。
 *       幂等键可由服务端自动生成</li>
 *   <li>{@link Mode#REPLAY}: 二次调用返回首次响应 (含响应码 + body), 适用幂等场景
 *       (网络重试, 客户端 SDK 自动重试, 支付/订单等高风险操作)。
 *       <b>要求客户端显式提供幂等键</b>, 否则切面直通不拦截</li>
 * </ul>
 *
 * <p><b>为何 REPLAY 拒绝服务端自动生成的键</b>: 自动键为
 * {@code userId + URI + body 摘要}, 无法区分“同一次请求的网络重试”与“用户隔一段时间
 * 再次提交相同内容”。后者会拿到首次的旧响应并被误认为成功, 即“假成功”——
 * 这类语义错误比不做幂等更危险。真正需要幂等保护的接口, 其客户端本就能生成唯一键。</p>
 *
 * 幂等 Key 优先级:
 * <ol>
 *   <li>{@link #keyHeader()} 指定的请求头 (默认 {@code Idempotency-Key}, RFC 草案风格)</li>
 *   <li>{@code X-Request-Token} (向后兼容现有用法)</li>
 *   <li>自动生成: userId + URI + body digest（<b>仅 {@link Mode#REJECT} 可用</b>）</li>
 * </ol>
 *
 * 用法:
 * <pre>
 * {@code
 * // 防抖模式 (按钮防双击, 短 TTL) —— 无需客户端配合
 * @Idempotent(expireSeconds = 10)
 * public Result submit() { ... }
 *
 * // 幂等模式 (网络重试, 长 TTL, 返回首次结果) —— 客户端须发送 Idempotency-Key
 * @Idempotent(mode = Mode.REPLAY, expireSeconds = 86400)
 * public Token pay(@RequestHeader("Idempotency-Key") String key) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等模式 (M6-02)
     */
    Mode mode() default Mode.REJECT;

    /**
     * 幂等操作的过期时间（秒）
     * <p>
     * REJECT 模式: 防抖窗口 (默认 10s, 适合按钮防双击)
     * <p>
     * REPLAY 模式: 响应缓存时长 (默认 86400s = 24h, 适合幂等重试场景)
     */
    int expireSeconds() default 10;

    /**
     * 错误提示 (仅 REJECT 模式生效)
     */
    String message() default "请求过于频繁，请稍后再试";

    /**
     * 幂等 Key 请求头 (默认 {@code Idempotency-Key}, RFC 草案风格)
     * 向后兼容: 若该头为空, 回退检查 {@code X-Request-Token}, 再回退自动生成
     */
    String keyHeader() default "Idempotency-Key";

    /**
     * 是否缓存响应 (仅 REPLAY 模式生效)
     * 默认 true; false 表示仅防并发 (类似 REJECT 但不缓存响应)
     */
    boolean storeResponse() default true;

    /**
     * 幂等语义模式
     */
    enum Mode {
        /**
         * 防抖: 二次调用抛异常 (拒绝)
         */
        REJECT,

        /**
         * 幂等: 二次调用返回首次响应 (重放)
         */
        REPLAY
    }
}