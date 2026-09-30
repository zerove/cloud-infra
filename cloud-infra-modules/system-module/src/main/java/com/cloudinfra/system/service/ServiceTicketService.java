package com.cloudinfra.system.service;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.system.domain.ServiceTicket;
import com.cloudinfra.system.mapper.ServiceTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 智能服务中心 - 工单管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTicketService {

    private final ServiceTicketMapper ticketMapper;
    private final IntelligentRoutingService routingService;
    private final NotificationApi notificationApi;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建智能服务工单并执行自动分发
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket createIntelligentTicket(String title, String content, Integer priority, Long assetId) {
        log.info("创建智能服务工单: {}", title);

        // 1. 初始化工单
        ServiceTicket ticket = new ServiceTicket();
        ticket.setTicketNo("TKT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
        ticket.setTitle(title);
        ticket.setContent(content);
        ticket.setPriority(priority);
        ticket.setAssetId(assetId);
        ticket.setStatus("OPEN");
        ticket.setCreateTime(LocalDateTime.now());

        // 2. 智能路由：自动寻找最合适的处理人
        Long assigneeId = routingService.routeToResponsibleUser(assetId);
        ticket.setAssigneeId(assigneeId);

        // 3. 持久化
        ticketMapper.insert(ticket);

        // 4. 发布事件（事务提交后触发监听器）
        eventPublisher.publishEvent(ticket);

        return ticket;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketCreated(ServiceTicket ticket) {
        triggerAssignNotification(ticket);
    }

    private void triggerAssignNotification(ServiceTicket ticket) {
        NotificationRequest request = new NotificationRequest(
            ticket.getAssigneeId(),
            NotificationType.SERVICE_TICKET,
            "【新工单】" + ticket.getTitle(),
            "您被指派处理工单 " + ticket.getTicketNo() + "，请及时处理。",
            List.of("SYSTEM"),
            null, null, null
        );
        
        notificationApi.send(request);
    }
}
