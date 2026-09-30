package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Idempotent;
import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.common.util.SecurityUtils;
import com.cloudinfra.framework.web.idempotency.IdempotencyEntry;
import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 幂等切面 (M6-02)
 *
 * 支持两种模式, 且对幂等键的要求不同:
 * <ul>
 *   <li>{@link Idempotent.Mode#REJECT} (默认): 二次调用抛异常 (防抖)。键可自动生成 ——
 *       防抖本就期望“短窗口内拒绝相同请求”</li>
 *   <li>{@link Idempotent.Mode#REPLAY}: 二次调用返回首次响应 (幂等重试)。
 *       <b>必须由客户端显式提供幂等键</b>, 否则直通不拦截</li>
 * </ul>
 *
 * <p><b>为何 REPLAY 拒绝自动生成的键</b>: 自动键为 {@code userId + URI + body 摘要},
 * 无法区分“同一次请求的网络重试”与“用户隔一段时间再次提交相同内容”。后者会拿到
 * 首次的旧响应并被误认为成功, 即“假成功”。这类语义错误比不做幂等更危险。
 * 真正需要幂等保护的接口 (支付、下单) 其客户端本就能生成唯一键。</p>
 *
 * Key 解析优先级:
 * <ol>
 *   <li>{@link Idempotent#keyHeader()} 指定的请求头 (默认 {@code Idempotency-Key})</li>
 *   <li>{@code X-Request-Token} (向后兼容)</li>
 *   <li>自动生成: userId + URI + body digest（仅 REJECT 模式可用）</li>
 * </ol>
 *
 * 限定到 controller 层避免对全 bean 做 AspectJ 签名解析拖慢启动.
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final IdempotencyStore store;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public IdempotentAspect(RedisTemplate<String, Object> redisTemplate, IdempotencyStore store) {
        this(redisTemplate, store, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public IdempotentAspect(RedisTemplate<String, Object> redisTemplate,
                            IdempotencyStore store,
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Pointcut("execution(* com.cloudinfra..controller..*(..))")
    private void controllerPointcut() {
    }

    @Around("controllerPointcut() && @annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. 解析请求上下文 (非 HTTP 调用, 如单元测试, 直接放行)
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attributes.getRequest();

        // 2. 计算幂等 Key Hash
        ResolvedKey resolved = resolveKey(request, idempotent.keyHeader());
        if (resolved == null) {
            // 无法生成 key, 直接放行 (避免误杀所有调用)
            return joinPoint.proceed();
        }

        // 3. REPLAY 必须由客户端显式提供幂等键。
        // 自动生成的键 (userId + URI + body 摘要) 无法区分"同一次请求的网络重试"
        // 与"用户隔一段时间再次提交相同内容": 后者会拿到首次的旧响应, 形成假成功。
        // REJECT 模式不受影响 —— 防抖本就期望"短窗口内拒绝相同请求"。
        if (idempotent.mode() == Idempotent.Mode.REPLAY && !resolved.clientSupplied()) {
            log.debug("[IdempotentAspect] 客户端未提供幂等键, REPLAY 降级为直通: {}",
                request.getRequestURI());
            return joinPoint.proceed();
        }

        // 4. 根据模式分发
        if (idempotent.mode() == Idempotent.Mode.REJECT) {
            return doReject(joinPoint, idempotent, resolved.hash());
        }
        return doReplay(joinPoint, idempotent, resolved.hash());
    }

    /**
     * 解析出的幂等键。
     *
     * @param hash            用于 Redis 的键摘要
     * @param clientSupplied  是否由客户端显式提供幂等键 (而非自动生成)
     */
    private record ResolvedKey(String hash, boolean clientSupplied) {
    }

    // ========== REJECT 模式: 防抖 ==========

    private Object doReject(ProceedingJoinPoint joinPoint, Idempotent idempotent, String keyHash) throws Throwable {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(
            IdempotencyEntry.lockKey(keyHash),
            "1",
            Duration.ofSeconds(idempotent.expireSeconds())
        );
        if (Boolean.FALSE.equals(ok)) {
            throw new GlobalException(idempotent.message());
        }
        return joinPoint.proceed();
    }

    // ========== REPLAY 模式: 幂等重试 ==========

    private Object doReplay(ProceedingJoinPoint joinPoint, Idempotent idempotent, String keyHash) throws Throwable {
        // 1. 检查已有响应 (二次调用路径)
        IdempotencyEntry cached = store.read(keyHash);
        if (cached != null) {
            log.debug("[IdempotentAspect] REPLAY 命中缓存, 直接返回首次响应 key={}", keyHash);
            return replayResponse(joinPoint, cached);
        }

        // 2. 尝试获得锁 (首次调用路径)
        if (!store.tryAcquire(keyHash, idempotent.expireSeconds())) {
            // 未获得锁: 另一线程正在执行, 抛异常让客户端稍后重试
            // (客户端拿到相同 Idempotency-Key 再调即可命中缓存)
            throw new GlobalException("请求正在处理中，请稍后重试");
        }

        // 3. 执行业务并在成功后缓存响应。
        //    用 try-finally 而非 catch: 业务失败时无论抛出什么 (含 Error) 都释放锁,
        //    让客户端能调整参数后重试; 成功时锁由缓存响应消费, 不再释放。
        boolean succeeded = false;
        try {
            Object result = joinPoint.proceed();
            succeeded = true;

            // 4. 缓存响应 (仅当 storeResponse=true, 默认 true)
            if (idempotent.storeResponse()) {
                // 契约: 缓存与重放保持方法返回类型一致, 避免首次 Result<T>、重放 String
                String json;
                try {
                    json = objectMapper.writeValueAsString(result);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // 序列化失败则不缓存，保持与未开启幂等一致的重试行为
                    store.releaseLock(keyHash);
                    return result;
                }
                store.storeResponse(keyHash,
                    IdempotencyEntry.success(200, json),
                    idempotent.expireSeconds());
            }
            return result;
        } finally {
            if (!succeeded) {
                store.releaseLock(keyHash);
            }
        }
    }

    /**
     * 重放首次响应。
     * 按方法返回类型反序列化，保证与首次调用类型契约一致，
     * 不再把 Result<T> 退化为 String。
     */
    private Object replayResponse(ProceedingJoinPoint joinPoint, IdempotencyEntry cached) {
        if (!cached.isSuccess()) {
            // 首次响应是异常, 二次调用重抛 (保持一致)
            throw new GlobalException(
                cached.getErrorMessage() != null ? cached.getErrorMessage() : "首次请求失败"
            );
        }
        if (cached.getBody() == null) {
            return null;
        }
        org.aspectj.lang.reflect.MethodSignature signature =
            (org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == String.class) {
            // 新缓存保存返回值的 JSON 表示 (字符串带引号)；旧缓存为 String.valueOf 原文。
            // 能按 JSON 字符串还原则还原，否则按原文重放，保持兼容。
            try {
                return objectMapper.readValue(cached.getBody(), String.class);
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                return cached.getBody();
            }
        }
        try {
            return objectMapper.readValue(cached.getBody(), objectMapper.constructType(returnType));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("[IdempotentAspect] 重放反序列化失败, 拒绝错误重放");
            // 带上 cause: 缓存体损坏是需要排查的真实故障, 丢掉堆栈等于让它无从追查
            throw new GlobalException("幂等缓存不可用，请稍后重试", e);
        }
    }

    // ========== Key 解析 ==========

    private ResolvedKey resolveKey(HttpServletRequest request, String keyHeader) {
        // 优先级 1: Idempotency-Key 头
        String token = request.getHeader(keyHeader);
        if (token == null || token.isBlank()) {
            // 优先级 2: X-Request-Token 头 (向后兼容)
            token = request.getHeader("X-Request-Token");
        }
        if (token != null && !token.isBlank()) {
            // 防御性: 幂等键长度限制 (避免 Redis key 超长)
            int suppliedLength = token.length();
            if (suppliedLength > 200) {
                // 只记录长度, 不记录键内容: 幂等键属客户端凭据, 不得入日志
                log.warn("[IdempotentAspect] 幂等键长度 {} 超过上限 200, 拒绝", suppliedLength);
                return null;
            }
            return new ResolvedKey(
                hash("idempotency:" + request.getRequestURI() + ":" + token.trim()), true);
        }
        // 优先级 3: 自动生成 (userId + URI + body digest)
        // 仅可用于 REJECT 防抖; REPLAY 会拒绝使用自动生成的键 (见 around)。
        Long userId = SecurityUtils.getUserId();
        String userPart = userId == null ? "anon" : String.valueOf(userId);
        String bodyDigest = digest(request);
        return new ResolvedKey(
            hash("idempotency:" + request.getRequestURI() + ":" + userPart + ":" + bodyDigest), false);
    }

    /**
     * 基于请求体/参数生成内容摘要
     */
    private String digest(HttpServletRequest request) {
        try {
            String body = (String) request.getAttribute("idempotent.body");
            if (body == null || body.isBlank()) {
                body = request.getQueryString();
            }
            if (body == null || body.isBlank()) {
                body = request.getContentType() == null ? "" : request.getContentType();
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必提供 SHA-256, 走到此处说明运行时被裁剪, 降级为固定摘要
            return "unknown";
        }
    }

    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必提供 SHA-256, 走到此处说明运行时被裁剪, 降级为弱摘要
            return Integer.toHexString(input.hashCode());
        }
    }
}
