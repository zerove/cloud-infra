package com.cloudinfra.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SensitiveDataRedactor} 守护测试。
 *
 * <p>方法契约: 脱敏必须在**序列化之前**按字段名完成。若实现退化为
 * "先 toString 再正则擦除", 下面 {@code #不可被正则绕过} 一组即会失败。
 */
class SensitiveDataRedactorTest {

    record Credentials(String username, String password) {
    }

    record Nested(Credentials credentials, String note) {
    }

    @Test
    void 明文口令不进入结果() {
        String out = SensitiveDataRedactor.redactArgs(new Object[]{new Credentials("admin", "s3cr3t-plaintext")});
        assertFalse(out.contains("s3cr3t-plaintext"), "明文口令泄漏: " + out);
        assertTrue(out.contains(SensitiveDataRedactor.MASK), out);
        assertTrue(out.contains("admin"), "非敏感字段应保留: " + out);
    }

    @Test
    void 嵌套对象内的口令同样被脱敏() {
        String out = SensitiveDataRedactor.redactArgs(new Object[]{new Nested(new Credentials("u", "inner-secret"), "n")});
        assertFalse(out.contains("inner-secret"), "嵌套层泄漏: " + out);
        assertTrue(out.contains("n"), out);
    }

    @Test
    void 列表与Map内的口令同样被脱敏() {
        String out = SensitiveDataRedactor.redactArgs(new Object[]{
                List.of(new Credentials("a", "list-secret")),
                Map.of("token", "map-secret", "keep", "visible")});
        assertFalse(out.contains("list-secret"), out);
        assertFalse(out.contains("map-secret"), out);
        assertTrue(out.contains("visible"), out);
    }

    @Test
    void 字段名大小写与分隔符变体都命中() {
        assertTrue(SensitiveDataRedactor.isSensitiveKey("apiKey"));
        assertTrue(SensitiveDataRedactor.isSensitiveKey("API-KEY"));
        assertTrue(SensitiveDataRedactor.isSensitiveKey("api_key"));
        assertTrue(SensitiveDataRedactor.isSensitiveKey("PASSWORD"));
        assertTrue(SensitiveDataRedactor.isSensitiveKey("newPassword"));
        assertFalse(SensitiveDataRedactor.isSensitiveKey("username"));
    }

    /**
     * 负向对照: 值里**不含** {@code key=value} 形态, 只有嵌套 JSON。
     * 依赖正则的实现会漏掉这类, 结构化替换不会。
     */
    @Test
    void 不可被正则绕过() {
        String out = SensitiveDataRedactor.redactArgs(new Object[]{new Nested(new Credentials("u", "json-only-secret"), "n")});
        assertFalse(out.contains("json-only-secret"), "嵌套 JSON 形态泄漏: " + out);
    }

    @Test
    void 非数据载体只记类型名不展开() {
        String out = SensitiveDataRedactor.redactArgs(new Object[]{Thread.currentThread()});
        assertTrue(out.contains("<Thread>"), out);
    }

    @Test
    void 空实参返回空数组字面量() {
        assertEquals("[]", SensitiveDataRedactor.redactArgs(null));
        assertEquals("[]", SensitiveDataRedactor.redactArgs(new Object[0]));
    }
}