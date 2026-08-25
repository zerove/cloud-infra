package com.cloudinfra.common.core.security;

/**
 * 权限缓存失效协议（发布端与订阅端共用的唯一契约）。
 *
 * <p>频道与消息格式此前散落在 4 个业务类中以字符串字面量重复定义，
 * 订阅端靠 "user:" 前缀解析，属于无类型分布式协议；现收口至此常量类，
 * 业务代码禁止自行拼接消息，必须经 system 侧 PermissionInvalidationService 发布。</p>
 *
 * <p>消息格式：</p>
 * <ul>
 *   <li>{@code user:{userId}} -> 清除指定用户的 permissions/userContexts 缓存</li>
 *   <li>{@code user:*}        -> 全量刷新（谨慎使用）</li>
 * </ul>
 */
public final class PermissionRefreshProtocol {

    /** Redis Pub/Sub 频道名。 */
    public static final String CHANNEL = "auth:refresh:channel";

    /** 用户级失效消息前缀。 */
    public static final String USER_PREFIX = "user:";

    private PermissionRefreshProtocol() {
    }

    /** 构造用户级失效消息。 */
    public static String userMessage(Long userId) {
        return USER_PREFIX + userId;
    }
}
