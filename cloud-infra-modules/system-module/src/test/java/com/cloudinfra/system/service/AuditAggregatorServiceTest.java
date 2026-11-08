package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计聚合服务单元测试（适配 master 契约）。
 * B3 回归：跨用户通知日志必须走内部 HMAC Feign 契约 getInternalLogs(userId)。
 */
@ExtendWith(MockitoExtension.class)
class AuditAggregatorServiceTest {

    @Mock
    private SysOperLogMapper operLogMapper;

    @Mock
    private NotificationApi notificationApi;

    @InjectMocks
    private AuditAggregatorService auditAggregatorService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysOperLog.class);
    }

    @Test
    void usesInternalLogsContract_forCrossUserAuditAggregation() {
        NotificationLogDTO notificationLog = new NotificationLogDTO();
        notificationLog.setNotificationId(7L);
        notificationLog.setChannelCode("EMAIL");
        notificationLog.setStatus("FAIL");
        notificationLog.setErrorMessage("smtp down");
        notificationLog.setCreateTime(LocalDateTime.of(2026, 10, 17, 11, 0));

        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(notificationApi.getInternalLogs(eq(100L))).thenReturn(Result.success(List.of(notificationLog)));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).status()).isEqualTo("FAIL");
        assertThat(timeline.get(0).source()).isEqualTo("NOTIFICATION");
        verify(notificationApi).getInternalLogs(eq(100L));
        verify(notificationApi, never()).getLogs(any());
    }

    @Test
    void mergesSystemAndNotificationSources() {
        SysOperLog oper = new SysOperLog();
        oper.setUserId(100L);
        oper.setStatus(0);
        oper.setTitle("测试操作");
        oper.setOperUrl("/test/endpoint");
        oper.setOperName("tester");
        oper.setCreateTime(LocalDateTime.of(2026, 10, 17, 10, 0));

        NotificationLogDTO notificationLog = new NotificationLogDTO();
        notificationLog.setNotificationId(7L);
        notificationLog.setChannelCode("EMAIL");
        notificationLog.setStatus("SUCCESS");
        notificationLog.setCreateTime(LocalDateTime.of(2026, 10, 17, 11, 0));

        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(oper));
        when(notificationApi.getInternalLogs(eq(100L))).thenReturn(Result.success(List.of(notificationLog)));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.stream().map(UnifiedAuditRecord::source)).containsExactlyInAnyOrder("SYSTEM", "NOTIFICATION");
    }

    @Test
    void returnsEmptyWhenNotificationApiFails() {
        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(notificationApi.getInternalLogs(eq(100L))).thenThrow(new RuntimeException("boom"));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).isEmpty();
        verify(notificationApi).getInternalLogs(eq(100L));
    }
}
