package com.cloudinfra.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.service.INotificationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * S2-B3 回归守护：legacy history/logs 禁止跨用户；/my/* 仅作用于当前用户。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerB3Test {

    @Mock
    private INotificationService notificationService;

    @InjectMocks
    private NotificationController controller;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyHistory_rejectsCrossUser() {
        setCurrentUser(10L);

        Result<List<NotificationHistoryResponse>> result =
                controller.getHistory(999L, null, null, 1, 10);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("/notification/my/history");
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    void legacyLogs_rejectsCrossUser() {
        setCurrentUser(10L);

        Result<List<NotificationLogDTO>> result = controller.getLogs(999L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("/notification/my/logs");
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    void legacyHistory_allowsSameUser() {
        setCurrentUser(10L);
        Notification n = new Notification();
        n.setId(1L);
        n.setType(NotificationType.EMAIL);
        n.setTitle("t");
        n.setContent("c");
        n.setStatus(NotificationStatus.SENT);
        n.setCreateTime(LocalDateTime.of(2026, 11, 8, 12, 0));
        given(notificationService.getNotificationHistory(eq(10L), eq(1), eq(10))).willReturn(List.of(n));

        Result<List<NotificationHistoryResponse>> result =
                controller.getHistory(10L, null, null, 1, 10);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).hasSize(1);
        then(notificationService).should().getNotificationHistory(10L, 1, 10);
    }

    @Test
    void myHistory_usesCurrentUserIgnoringClientUserId() {
        setCurrentUser(10L);
        given(notificationService.getNotificationHistory(eq(10L), anyInt(), anyInt())).willReturn(List.of());

        Result<List<NotificationHistoryResponse>> result =
                controller.getMyHistory(null, null, 1, 10);

        assertThat(result.isSuccess()).isTrue();
        then(notificationService).should().getNotificationHistory(10L, 1, 10);
    }

    @Test
    void myLogs_usesCurrentUser() {
        setCurrentUser(10L);
        NotificationLog log = new NotificationLog();
        log.setNotificationId(7L);
        log.setChannelCode("email");
        log.setStatus("SUCCESS");
        log.setCreateTime(LocalDateTime.of(2026, 11, 8, 12, 0));
        given(notificationService.getLogs(eq(10L))).willReturn(List.of(log));

        Result<List<NotificationLogDTO>> result = controller.getMyLogs();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).hasSize(1);
        then(notificationService).should().getLogs(10L);
    }

    private static void setCurrentUser(Long userId) {
        LoginUser user = LoginUser.builder().userId(userId).username("u" + userId).build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
