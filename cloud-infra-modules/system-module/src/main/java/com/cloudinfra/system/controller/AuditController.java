package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.service.AuditAggregatorService;
import com.cloudinfra.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一审计控制器
 */
@RestController
@RequestMapping("/system/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditAggregatorService auditAggregatorService;
    private final ISysUserService userService;

    /**
     * 获取用户全链路行为时间线
     *
     * <p><b>租户边界（2026-11-12 修复, issue 36）</b>：本端点只校验
     * {@code system:audit:list}，此前**不校验被查询的 userId 是否属于调用方租户**，
     * 而审计所依赖的 {@code sys_oper_log} / {@code sys_notification_log} 也没有租户维度
     * （无 {@code tenant_id} 列、Mapper 无 {@code @TenantFilter}），
     * 于是任一租户的审计员都能读到另一租户用户的审计记录 ——
     * 实测证据见 {@code .scratch/2026-q3-roadmap/issues/36-cross-tenant-audit-timeline-leak.md}。</p>
     *
     * <p>修法：用**租户过滤后的**用户可见性检查（{@code SysUserMapper} 带
     * {@code @TenantFilter}，查询自动追加 {@code tenant_id = ?}）当作授权边界。
     * 目标用户不在调用方租户内 → 查不到 → 直接拒绝，
     * 不进入聚合（也就不会触发跨租户的日志查询）。</p>
     *
     * <p>两个刻意的边界：</p>
     * <ul>
     *   <li>**没有租户上下文时放行**（内部可信调用路径）：{@code @TenantFilter} 默认
     *       {@code rejectWhenMissing=false}，无上下文即不追加租户条件，行为与修复前一致。</li>
     *   <li>拒绝时统一 403 而非 404：避免用"存在/不存在"的差异泄露他租户用户是否存在。</li>
     * </ul>
     */
    @PreAuthorize("@ss.hasPermi('system:audit:list')")
    @GetMapping("/timeline")
    public Result<List<UnifiedAuditRecord>> getTimeline(
            @RequestParam Long userId) {
        try {
            userService.selectUserById(userId);
        } catch (SystemBusinessException e) {
            // 租户过滤后查不到 = 目标用户不在调用方租户内（或根本不存在）
            return Result.fail(403, "禁止查询其他租户用户的审计记录");
        }
        return Result.success(auditAggregatorService.getUnifiedTimeline(userId));
    }
}
