package com.cloudinfra.api.notification.fallback;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NotificationApi 降级工厂
 *
 * <p>分页查询降级返回 {@link PageResult#empty()} (records 空, total/窗口均为 0) ——
 * 与"查询成功但结果为空"可区分: 后者 total 同为 0, 但窗口回显请求值。</p>
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
            public Result<PageResult<NotificationHistoryResponse>> getHistory(Long userId, NotificationStatus status,
                    NotificationType type, PageQuery pageQuery) {
                return Result.success(PageResult.empty());
            }

            @Override
            public Result<Void> markAsRead(Long id) {
                return Result.fail(503, "通知服务暂不可用");
            }

            @Override
            public Result<PageResult<NotificationDTO>> searchNotifications(String keyword, PageQuery pageQuery) {
                return Result.success(PageResult.empty());
            }

            @Override
            public Result<PageResult<NotificationLogDTO>> getLogs(Long userId, PageQuery pageQuery) {
                return Result.success(PageResult.empty());
            }
        };
    }
}