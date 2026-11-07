package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
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
import static org.mockito.Mockito.when;

/**
 * 审计聚合服务单元测试
 * 覆盖: 操作日志 status 0/1 到 SUCCESS/FAIL 的真实映射 (不得硬编码), 通知日志状态透传
 */
@ExtendWith(MockitoExtension.class)
public class AuditAggregatorServiceTest {

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
    void failedOperLogIsReportedAsFail() {
        SysOperLog failed = operLog(1, LocalDateTime.of(2026, 10, 17, 10, 0));
        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(failed));
        when(notificationApi.getLogs(eq(100L), any(PageQuery.class))).thenReturn(Result.success(PageResult.empty()));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).status()).isEqualTo("FAIL");
        assertThat(timeline.get(0).source()).isEqualTo("SYSTEM");
    }

    @Test
    void successfulOperLogIsReportedAsSuccess() {
        SysOperLog ok = operLog(0, LocalDateTime.of(2026, 10, 17, 10, 0));
        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ok));
        when(notificationApi.getLogs(eq(100L), any(PageQuery.class))).thenReturn(Result.success(PageResult.empty()));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void notificationLogStatusIsPassedThrough() {
        NotificationLogDTO failedNotification = new NotificationLogDTO();
        failedNotification.setNotificationId(7L);
        failedNotification.setChannelCode("EMAIL");
        failedNotification.setStatus("FAIL");
        failedNotification.setErrorMessage("smtp down");
        failedNotification.setCreateTime(LocalDateTime.of(2026, 10, 17, 11, 0));

        when(operLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(notificationApi.getLogs(eq(100L), any(PageQuery.class)))
            .thenReturn(Result.success(PageResult.of(List.of(failedNotification), 1L, 1L, 100L)));

        List<UnifiedAuditRecord> timeline = auditAggregatorService.getUnifiedTimeline(100L);

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).status()).isEqualTo("FAIL");
        assertThat(timeline.get(0).source()).isEqualTo("NOTIFICATION");
    }

    private static SysOperLog operLog(int status, LocalDateTime createTime) {
        SysOperLog log = new SysOperLog();
        log.setUserId(100L);
        log.setStatus(status);
        log.setTitle("测试操作");
        log.setOperUrl("/test/endpoint");
        log.setOperName("tester");
        log.setCreateTime(createTime);
        return log;
    }
}
