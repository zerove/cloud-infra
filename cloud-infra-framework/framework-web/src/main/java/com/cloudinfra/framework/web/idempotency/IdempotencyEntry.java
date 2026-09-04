package com.cloudinfra.framework.web.idempotency;

import java.time.Duration;

/**
 * 幂等响应缓存条目 (M6-02)
 *
 * 存储于 Redis, 用于 REPLAY 模式下二次调用返回首次响应.
 *
 * JSON 字段:
 * - statusCode: HTTP 状态码 (200/201/400/500 等)
 * - body: 响应体 (JSON 字符串)
 * - errorClass: 异常类名 (失败响应, 用于二次调用识别)
 * - errorMessage: 异常消息
 *
 * 注意: 不缓存响应头 (Set-Cookie 等动态头), 仅缓存状态码与 body.
 *       若业务需要缓存头, 可扩展为 Map<String, String>.
 */
public class IdempotencyEntry {

    /** 状态码 (200 等), null 表示异常响应 */
    private Integer statusCode;

    /** 响应体 (JSON 字符串) */
    private String body;

    /** 异常类名 (仅失败响应存在) */
    private String errorClass;

    /** 异常消息 (仅失败响应存在) */
    private String errorMessage;

    public IdempotencyEntry() {
    }

    public static IdempotencyEntry success(int statusCode, String body) {
        IdempotencyEntry e = new IdempotencyEntry();
        e.statusCode = statusCode;
        e.body = body;
        return e;
    }

    public static IdempotencyEntry failure(String errorClass, String errorMessage) {
        IdempotencyEntry e = new IdempotencyEntry();
        e.errorClass = errorClass;
        e.errorMessage = errorMessage;
        return e;
    }

    public boolean isSuccess() {
        return statusCode != null;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Redis Key: 响应缓存
     */
    public static String responseKey(String keyHash) {
        return "idempotency:" + keyHash + ":response";
    }

    /**
     * Redis Key: 锁标记 (用于检测 IN_FLIGHT 状态)
     */
    public static String lockKey(String keyHash) {
        return "idempotency:" + keyHash + ":lock";
    }

    /**
     * Redis Key: 状态标记 (IN_FLIGHT / COMPLETED)
     */
    public static String statusKey(String keyHash) {
        return "idempotency:" + keyHash + ":status";
    }

    /** 状态值: 首次调用执行中 */
    public static final String STATUS_IN_FLIGHT = "IN_FLIGHT";

    /** 状态值: 首次调用已完成 (响应已缓存) */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 锁 TTL: 首次调用最长执行时间 (默认 30s) */
    public static final Duration LOCK_TTL = Duration.ofSeconds(30);
}