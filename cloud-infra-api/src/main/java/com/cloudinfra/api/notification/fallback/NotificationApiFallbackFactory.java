package com.cloudinfra.api.notification.fallback;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * NotificationApi 降级工厂
 */
@Component
public class NotificationApiFallbackFactory implements FallbackFactory<NotificationApi> {

    private static final Logger log = LoggerFactory.getLogger(NotificationApiFallbackFactory.class);

    @Override
    public NotificationApi create(Throwable cause) {
        log.warn("NotificationApi fallback triggered: {}", cause.getMessage());
        return new NotificationApi() {
            @Override
            public Result<NotificationResponse> send(NotificationRequest request) {
                return Result.fail(503, "通知服务暂不可用");
            }

            @Override
            public Result<List<NotificationHistoryResponse>> getHistory(Long userId, NotificationStatus status,
                    NotificationType type, Integer page, Integer size) {
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<Void> markAsRead(Long id) {
                return Result.fail(503, "通知服务暂不可用");
            }

            @Override
            public Result<List<NotificationDTO>> searchNotifications(String keyword, Integer page, Integer size) {
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<NotificationLogDTO>> getLogs(Long userId, Integer page, Integer size) {
                return Result.success(Collections.emptyList());
            }
        };
    }
}