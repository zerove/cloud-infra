package com.cloudinfra.notification.mq;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import com.cloudinfra.notification.service.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 通知分发消费者 (issue 27 决策 2)
 *
 * <p>消息体为通知 ID。消费流程:
 * <ol>
 *   <li>CAS 抢占 ({@code PENDING → DISPATCHING})。MQ 重投与补扫重投可能命中同一条记录,
 *       靠这一行 UPDATE 判定归属, 未抢到直接确认</li>
 *   <li>执行渠道分发 ({@link NotificationDispatcher})</li>
 *   <li>结果落库: 成功置 DISPATCHED; 全渠道失败退回 PENDING 等待补扫重投</li>
 * </ol>
 *
 * <p><b>为何不在此处抛异常</b>: {@code NotificationDispatcher} 内部已把
 * 渠道失败落库并退回 PENDING, 由补扫任务按时间间隔重投。抛异常在此没有收益 ——
 * 本项目未对原生监听器配置 MQ 级重试, 消息会被直接确认或重新入队, 反而可能
 * 造成与补扫机制重复的立即重投风暴。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatchConsumer {

    private final NotificationDispatchMapper dispatchMapper;
    private final NotificationDispatcher dispatcher;

    @RabbitListener(queues = "notification.dispatch.queue", concurrency = "3-10")
    public void handleDispatch(String payload) {
        Long notificationId = parseId(payload);
        if (notificationId == null) {
            log.warn("[Dispatch-Consumer] 消息体非法, 直接丢弃: {}", payload);
            return;
        }

        int claimed = dispatchMapper.claimDispatch(notificationId, DispatchStatus.PENDING);
        if (claimed == 0) {
            // 已被其他消费者/补扫实例抢占, 或已处于终态 (DISPATCHED/FAILED/DISPATCHING)
            log.debug("[Dispatch-Consumer] 未抢到分发权, 跳过: id={}", notificationId);
            return;
        }

        log.info("[Dispatch-Consumer] 开始处理分发: id={}", notificationId);
        try {
            dispatcher.dispatch(notificationId);
        } catch (RuntimeException unexpected) {
            // 分发器内部已处理渠道级失败; 到这里说明是未预期错误 (如 DB 不可用)。
            // 退回 PENDING 让补扫任务重投, 不在此抛异常避免 MQ 立即重试风暴。
            dispatchMapper.releaseDispatchClaim(notificationId);
            log.error("[Dispatch-Consumer] 分发异常, 退回待分发: id={}", notificationId, unexpected);
        }
    }

    private Long parseId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(payload.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
