package com.cloudinfra.notification.service.impl;

import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.domain.vo.NotificationVO;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.service.ChannelFactory;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationMapper notificationMapper;
    private final ChannelFactory channelFactory;

    @Override
    @Transactional
    public void sendNotification(NotificationSendRequest request) {
        // 1. 记录初始通知状态
        Notification notification = new Notification();
        notification.setUserId(request.getUserId());
        notification.setTitle(request.getTitle());
        notification.setContent(request.getContent());
        notification.setType(request.getType().name());
        notification.setStatus("SENDING");
        notificationMapper.insert(notification);

        // 2. 使用虚拟线程执行多渠道并发发送
        // 在 JDK 25 中，我们直接使用虚拟线程执行器
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> targetChannels = request.getChannels() != null 
                ? request.getChannels() 
                : List.of("websocket"); // 默认渠道

            List<CompletableFuture<Void>> futures = targetChannels.stream()
                .map(code -> CompletableFuture.runAsync(() -> {
                    channelFactory.getChannel(code).ifPresentOrElse(
                        channel -> {
                            try {
                                channel.send(request.getUserId(), request.getTitle(), request.getContent());
                                log.info("Channel {} send success for user {}", code, request.getUserId());
                            } catch (Exception e) {
                                log.error("Channel {} send failed: {}", code, e.getMessage());
                                throw new RuntimeException(e);
                            }
                        },
                        () -> log.warn("Channel {} not found", code)
                    );
                }, executor))
                .collect(Collectors.toList());

            // 等待所有任务完成 (或者设置总超时时间)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 3. 更新最终状态
            notification.setStatus("SUCCESS");
        } catch (Exception e) {
            log.error("Notification orchestration failed", e);
            notification.setStatus("FAILED");
        } finally {
            notificationMapper.updateById(notification);
        }
    }

    @Override
    public List<Notification> getNotificationHistory(Long userId) {
        return notificationMapper.selectByUserId(userId);
    }
}
