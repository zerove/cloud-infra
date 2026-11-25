package com.cloudinfra.common.core.tenant;

/**
 * 租户标识传输载体的唯一名字来源。
 *
 * <p><b>背景 (2026-10-18, 审计缺陷 D2)</b>: JWT claim 名 {@code "tenantId"} 此前在
 * auth ({@code AuthTokenServiceImpl}) 与网关 ({@code AuthEvaluator}) 各持一份字面量,
 * 且两处注释都声称"由 {@code TenantClaimNameConsistencyTest} 跨模块断言一致" ——
 * 而该测试<b>从未存在</b>。注释把一个未兑现的承诺当既成事实写下, 下一个人便不会再补,
 * 与"门禁自己从未被调用"同源。现两端引用同一常量: 一致性由构造保证,
 * 不再是需要跨模块测试去比对的不变量。
 *
 * <p>字面量由 {@code TenantClaimsTest} 锁定 —— 改名不会编译失败,
 * 只会让租户隔离无声失效 (auth 写进 token, 网关读不到)。
 */
public final class TenantClaims {

    /** JWT 中的租户 claim 名: auth 签发时写入, 网关鉴权时读取, 两端必须一致。 */
    public static final String TENANT_ID_CLAIM = "tenantId";

    private TenantClaims() {
    }
}
