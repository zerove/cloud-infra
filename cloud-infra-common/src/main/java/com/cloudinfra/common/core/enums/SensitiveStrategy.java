package com.cloudinfra.common.core.enums;

/**
 * 脱敏策略枚举
 */
public enum SensitiveStrategy {
    // 手机号 (掩码: 138****1234)
    PHONE,
    // 身份证 (掩码: 110101********1234)
    ID_CARD,
    // 姓名 (掩码: 张* 或 **三)
    NAME,
    // 邮箱 (掩码: a****@gmail.com)
    EMAIL,
    // 地址 (仅保留前6位)
    ADDRESS
}
