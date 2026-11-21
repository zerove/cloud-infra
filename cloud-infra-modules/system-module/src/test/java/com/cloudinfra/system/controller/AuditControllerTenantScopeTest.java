package com.cloudinfra.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.service.AuditAggregatorService;
import com.cloudinfra.system.service.ISysUserService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code AuditController} 的租户边界守护（issue 36）。
 *
 * <p>守护的是一条**安全属性**：调用方**只能**查本租户用户的审计时间线。
 * 修复前该端点只校验 {@code system:audit:list}，任一租户的审计员都能读到
 * 另一租户用户的记录（真实运行时证据见 issue 36）。</p>
 *
 * <p>负向对照：把控制器里的可见性检查去掉 → {@link #targetUserOutsideTenant_isRejected()}
 * 必须失败（它会变成 200 + 他人时间线）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditControllerTenantScopeTest {

    @Mock
    private AuditAggregatorService auditAggregatorService;

    @Mock
    private ISysUserService userService;

    @InjectMocks
    private AuditController auditController;

    @Test
    void targetUserInsideTenant_returnsTimeline() {
        SysUser visible = new SysUser();
        visible.setUserId(1L);
        visible.setTenantId("default");
        when(userService.selectUserById(1L)).thenReturn(visible);
        when(auditAggregatorService.getUnifiedTimeline(1L)).thenReturn(List.of(
                new UnifiedAuditRecord(LocalDateTime.now(), "1", "发送通知 [email]",
                        "3", "SUCCESS", null, "NOTIFICATION")));

        Result<List<UnifiedAuditRecord>> result = auditController.getTimeline(1L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        verify(auditAggregatorService).getUnifiedTimeline(1L);
    }

    @Test
    void targetUserOutsideTenant_isRejected() {
        // 租户过滤后的查询查不到 = 目标用户不在调用方租户内
        when(userService.selectUserById(anyLong()))
                .thenThrow(new SystemBusinessException(SystemExceptionCode.USER_NOT_FOUND));

        Result<List<UnifiedAuditRecord>> result = auditController.getTimeline(1L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getData()).isNull();
        // 关键: 拒绝时**不得**触发聚合（否则等于先取数再拒绝）
        verify(auditAggregatorService, never()).getUnifiedTimeline(anyLong());
    }
}
