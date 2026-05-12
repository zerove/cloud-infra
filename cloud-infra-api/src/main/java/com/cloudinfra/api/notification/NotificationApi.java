package com.cloudinfra.api.notification;

import com.cloudinfra.api.notification.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "notification-service")
public interface NotificationApi {
    @PostMapping("/notification/send")
    Result<NotificationResponse> send(@RequestBody NotificationRequest request);

    @GetMapping("/notification/history")
    Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size);

    @PutMapping("/notification/read/{id}")
    Result<Void> markAsRead(@PathVariable("id") Long id);
}
