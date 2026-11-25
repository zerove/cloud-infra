package com.cloudinfrait.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.system.service.AuditAggregatorService;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守护 {@code AuditAggregatorService} 的租户传播语义（issue 36 深化防御）。
 *
 * <p>构造别出心裁的负向对照：Mockito answer 里直接读
 * {@link TenantContext#getTenantId()}，读到虚拟线程时租户会怎样？
 * 现今的链路是调用线程捕获 → 任务设置 → 任务内查询。若该契约被破坏
 * （例如改回 lambda 内读 ThreadLocal），这里立刻变红，而不等真机排查。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditAggregatorServiceTenantPropagationTest {

    @Mock
    private SysOperLogMapper operLogMapper;

    @Mock
    private NotificationApi notificationApi;

    @InjectMocks
    private AuditAggregatorService service;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("虚拟线程任务内观察到的租户 == 调用线程捕获的租户")
    void asyncTasksObserveCapturedRequestTenant() {
        TenantContext.setTenantId("tenant-a");
        AtomicReference<String> operTenant = new AtomicReference<>("<unset>");
        AtomicReference<String> notifyTenant = new AtomicReference<>("<unset>");
        when(operLogMapper.selectList(any())).thenAnswer(inv -> {
            operTenant.set(TenantContext.getTenantId().orElse(null));
            return List.of();
        });
        when(notificationApi.getInternalLogs(anyLong(), any())).thenAnswer(inv -> {
            notifyTenant.set(TenantContext.getTenantId().orElse(null));
            return null; // 空列表收敛
        });

        service.getUnifiedTimeline(7L);

        assertThat(operTenant).hasValue("tenant-a");
        assertThat(notifyTenant).hasValue("tenant-a");
    }

    @Test
    @DisplayName("无租户上下文时任务观察到 default（不引入空上下文注入）")
    void asyncTasksObserveDefaultWithoutRequestTenant() {
        AtomicReference<String> operTenant = new AtomicReference<>("<unset>");
        when(operLogMapper.selectList(any())).thenAnswer(inv -> {
            operTenant.set(TenantContext.getTenantId().orElse(null));
            return List.of();
        });
        when(notificationApi.getInternalLogs(anyLong(), any())).thenAnswer(inv -> null);

        service.getUnifiedTimeline(7L);

        assertThat(operTenant).hasValue("default");
    }
}
