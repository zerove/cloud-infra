package com.cloudinfra.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构化敏感数据脱敏器。
 *
 * <p><b>为什么不能用正则</b>: 既有实现 ({@code WebLoggingAspect.maskSensitiveParams}) 先对参数做
 * {@code Arrays.deepToString(args)} 再用正则替换 {@code password=xxx}。这有两个同源缺陷:
 * <ol>
 *   <li><b>泄漏发生在 toString 阶段</b> —— 明文口令已经被拼进字符串, 正则只是事后擦字,
 *       而正则只认 {@code key=value} / {@code key:"value"} 两种形态; 嵌套对象、数组元素、
 *       字段名大小写/下划线变体都能绕过。</li>
 *   <li><b>先泄漏后擦除</b> —— 只要正则没匹配上, 明文就随字符串一起进入日志与数据库。</li>
 * </ol>
 *
 * <p>本实现改为: <b>先按字段名在对象图上替换, 再序列化</b>。敏感值从不进入任何中间字符串,
 * 因此不存在"擦除失败"的可能。
 *
 * <p>用法: {@link #redactArgs(Object[])} 用于审计落库与请求日志。
 */
public final class SensitiveDataRedactor {

    /** 脱敏后的固定占位值。 */
    public static final String MASK = "***MASKED***";

    /**
     * 敏感字段名 (统一小写)。归一化时会去掉 {@code _} 与 {@code -},
     * 使 {@code api_key} / {@code API-KEY} / {@code apiKey} 都能命中。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "oldpassword", "newpassword", "confirmpassword",
            "secret", "token", "accesstoken", "refreshtoken", "idtoken",
            "apikey", "apisecret", "authorization", "credential", "credentials",
            "privatekey", "internalcallsecret", "jwtsecret"
    );

    /** 只对"数据载体"做深度序列化, 其余类型只记类型名 —— 避免把请求/响应/流对象拖进日志。 */
    private static final String DATA_PACKAGE_PREFIX = "com.cloudinfra";

    /** 单条参数的最大长度, 防止超大集合把审计表撑爆。 */
    private static final int MAX_LENGTH = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SensitiveDataRedactor() {
    }

    /**
     * 脱敏一组方法实参, 返回可直接落库/记日志的紧凑 JSON 片段。
     *
     * @param args 方法实参, 可为 null
     * @return 形如 {@code [{"username":"admin","password":"***MASKED***"}]}; 无实参时返回 {@code "[]"}
     */
    public static String redactArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        ArrayNode root = MAPPER.createArrayNode();
        for (Object arg : args) {
            root.add(toSafeNode(arg));
        }
        return truncate(root.toString());
    }

    /**
     * 判断字段名是否为敏感字段。供其它模块 (如审计链路的字段级过滤) 复用, 避免各写一套。
     */
    public static boolean isSensitiveKey(String fieldName) {
        return fieldName != null && SENSITIVE_KEYS.contains(normalize(fieldName));
    }

    /** 把单个实参转成可安全记录的节点; 任何环节失败都退化为"仅类型名", 绝不调用 toString。 */
    private static JsonNode toSafeNode(Object arg) {
        if (arg == null) {
            return MAPPER.nullNode();
        }
        if (!isDataCarrier(arg)) {
            return TextNode.valueOf("<" + arg.getClass().getSimpleName() + ">");
        }
        try {
            JsonNode node = MAPPER.valueToTree(arg);
            return redactNode(node);
        } catch (RuntimeException e) {
            // 循环引用 / 不可序列化 getter 等: 退化为类型名。宁可信息少, 不可泄漏。
            return TextNode.valueOf("<" + arg.getClass().getSimpleName() + ">");
        }
    }

    /** 递归替换: 字段名命中敏感集合时, 整个值替换为 MASK, 不再下钻。 */
    private static JsonNode redactNode(JsonNode node) {
        if (node == null) {
            return MAPPER.nullNode();
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    field.setValue(TextNode.valueOf(MASK));
                } else {
                    redactNode(field.getValue());
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, redactNode(array.get(i)));
            }
            return array;
        }
        return node;
    }

    /**
     * 只展开标量、容器, 以及**本项目自有包**下的 POJO —— 那才是审计要留痕的对象。
     * JDK / servlet / 各类流一律只记类型名: 全量序列化会把请求体与流状态机拖进审计行, 且易遇循环引用。
     */
    private static boolean isDataCarrier(Object arg) {
        Class<?> type = arg.getClass();
        if (isSimple(type)) {
            return true;
        }
        if (type.isArray()) {
            return isDataCarrier(Array.get(arg, 0));
        }
        if (arg instanceof Collection<?> collection) {
            return collection.stream().allMatch(SensitiveDataRedactor::isDataCarrier);
        }
        if (arg instanceof Map<?, ?> map) {
            return map.keySet().stream().allMatch(k -> k != null && isSimple(k.getClass()))
                    && map.values().stream().allMatch(SensitiveDataRedactor::isDataCarrier);
        }
        return type.getName().startsWith(DATA_PACKAGE_PREFIX);
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || CharSequence.class.isAssignableFrom(type)
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || java.util.UUID.class == type;
    }

    private static String normalize(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LENGTH) + "...(truncated)";
    }
}