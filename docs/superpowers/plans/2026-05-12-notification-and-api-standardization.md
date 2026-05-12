# Notification Service & API Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a complete notification loop (async send -> persist -> query) and standardize all cross-service API contracts in `cloud-infra-api`.

**Architecture:** 
1. **Contract-First**: Define DTOs and FeignClients in `cloud-infra-api` first.
2. **Event-Driven**: Use Spring Cloud Stream for asynchronous notification triggering.
3. **Persistence**: Store notification history in PostgreSQL for traceability.

**Tech Stack:** Spring Boot 4, JDK 25 (Virtual Threads), Spring Cloud Stream, MyBatis Plus, PostgreSQL, OpenFeign.

---

## Phase 1: API Standardization (Contract Layer)

### Task 1: Notification API Contracts
**Files:**
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/notification/dto/NotificationRequest.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/notification/dto/NotificationResponse.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/notification/dto/NotificationHistoryResponse.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/notification/NotificationApi.java`

- [ ] **Step 1: Create `NotificationRequest` DTO**
```java
package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class NotificationRequest implements Serializable {
    private Long userId;
    private String type; // SYSTEM, MESSAGE, ALERT
    private String title;
    private String content;
    private String target; // Email or Phone
}
```

- [ ] **Step 2: Create `NotificationResponse` DTO**
```java
package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class NotificationResponse implements Serializable {
    private Long notificationId;
    private String status;
}
```

- [ ] **Step 3: Create `NotificationHistoryResponse` DTO**
```java
package com.cloudinfra.api.notification.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class NotificationHistoryResponse implements Serializable {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Integer status; // 0-unread, 1-read
    private LocalDateTime createTime;
}
```

- [ ] **Step 4: Create `NotificationApi` FeignClient**
```java
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
```

- [ ] **Step 5: Commit**
```bash
git add cloud-infra-api/src/main/java/com/cloudinfra/api/notification/
git commit -m "feat(api): define notification service contracts"
```

### Task 2: Search API Contracts
**Files:**
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/search/dto/SearchRequest.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/search/dto/SearchResponse.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/search/SearchApi.java`

- [ ] **Step 1: Create `SearchRequest`**
```java
package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class SearchRequest implements Serializable {
    private String keyword;
    private Integer page;
    private Integer size;
}
```

- [ ] **Step 2: Create `SearchResponse`** (Generic wrapper for search results)
```java
package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class SearchResponse<T> implements Serializable {
    private List<T> data;
    private long total;
}
```

- [ ] **Step 3: Create `SearchApi` FeignClient**
```java
package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "search-service")
public interface SearchApi {
    @GetMapping("/search/operlog")
    Result<List<?>> searchOperLog(@RequestParam String keyword, 
                                 @RequestParam(defaultValue = "0") int page, 
                                 @RequestParam(defaultValue = "10") int size);
}
```

- [ ] **Step 4: Commit**
```bash
git add cloud-infra-api/src/main/java/com/cloudinfra/api/search/
git commit -m "feat(api): define search service contracts"
```

### Task 3: File API Contracts
**Files:**
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/file/dto/FileUploadRequest.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/file/dto/FileInfoResponse.java`
- Create: `cloud-infra-api/src/main/java/com/cloudinfra/api/file/FileApi.java`

- [ ] **Step 1: Create `FileUploadRequest`**
```java
package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class FileUploadRequest implements Serializable {
    private String fileName;
    private String fileType;
}
```

- [ ] **Step 2: Create `FileInfoResponse`**
```java
package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class FileInfoResponse implements Serializable {
    private Long id;
    private String fileName;
    private String url;
    private Long size;
}
```

- [ ] **Step 3: Create `FileApi` FeignClient**
```java
package com.cloudinfra.api.file;

import com.cloudinfra.api.file.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "file-service")
public interface FileApi {
    @PostMapping("/file/upload")
    Result<FileInfoResponse> upload(@RequestBody FileUploadRequest request);

    @GetMapping("/file/sys/file/{id}")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);
}
```

- [ ] **Step 4: Commit**
```bash
git add cloud-infra-api/src/main/java/com/cloudinfra/api/file/
git commit -m "feat(api): define file service contracts"
```

---

## Phase 2: Notification Service Implementation

### Task 4: Database Schema & Persistence Layer
**Files:**
- Modify: `cloud-infra-modules/notification-service/src/main/resources/schema.sql`
- Create: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/domain/Notification.java`
- Create: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/mapper/NotificationMapper.java`
- Create: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/repository/NotificationRepository.java`

- [ ] **Step 1: Update `schema.sql`**
```sql
CREATE TABLE IF NOT EXISTS sys_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_time TIMESTAMP,
    CONSTRAINT idx_notification_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
);
CREATE INDEX IF NOT EXISTS idx_notification_user_status ON sys_notification(user_id, status);
```

- [ ] **Step 2: Create `Notification` Entity**
```java
package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Integer status; // 0-unread, 1-read
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime readTime;
}
```

- [ ] **Step 3: Create `NotificationMapper`**
```java
package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.Notification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
```

- [ ] **Step 4: Create `NotificationRepository`**
```java
package com.cloudinfra.notification.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationRepository extends ServiceImpl<NotificationMapper, Notification> {
    
    public List<Notification> findHistory(Long userId, Integer status, String type) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(status != null, Notification::getStatus, status)
               .eq(type != null, Notification::getType, type)
               .orderByDesc(Notification::getCreateTime);
        return this.list(wrapper);
    }
}
```

- [ ] **Step 5: Commit**
```bash
git add cloud-infra-modules/notification-service/src/main/resources/schema.sql 
        cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/domain/ 
        cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/mapper/ 
        cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/repository/
git commit -m "feat(notification): implement persistence layer"
```

### Task 5: Service Implementation
**Files:**
- Modify: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/service/INotificationService.java`
- Modify: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/service/impl/NotificationServiceImpl.java`

- [ ] **Step 1: Update `INotificationService` interface**
```java
package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.Notification;
import java.util.List;

public interface INotificationService {
    void sendEmail(String to, String subject, String content);
    void sendSms(String phone, String content);
    
    // New methods
    void saveNotification(Long userId, String type, String title, String content);
    List<Notification> getHistory(Long userId, Integer status, String type);
    void markAsRead(Long notificationId);
}
```

- [ ] **Step 2: Update `NotificationServiceImpl`**
```java
package com.cloudinfra.notification.service.impl;

import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.notification.service.INotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    public void sendEmail(String to, String subject, String content) {
        log.info("Sending Email to: {}, Subject: {}, Content: {}", to, subject, content);
    }

    @Override
    public void sendSms(String phone, String content) {
        log.info("Sending SMS to: {}, Content: {}", phone, content);
    }

    @Override
    @Transactional
    public void saveNotification(Long userId, String type, String title, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setStatus(0);
        notificationRepository.save(notification);
        log.info("Notification saved for user {}: {}", userId, title);
    }

    @Override
    public List<Notification> getHistory(Long userId, Integer status, String type) {
        return notificationRepository.findHistory(userId, status, type);
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId) {
        Notification notification = notificationRepository.getById(notificationId);
        if (notification != null) {
            notification.setStatus(1);
            notification.setReadTime(LocalDateTime.now());
            notificationRepository.updateById(notification);
        }
    }
}
```

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/service/
git commit -m "feat(notification): implement business logic for history and status"
```

### Task 6: Controller & MQ Integration
**Files:**
- Create: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/controller/NotificationController.java`
- Modify: `cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/mq/NotificationConsumer.java`

- [ ] **Step 1: Create `NotificationController`**
```java
package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.dto.*;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "通知管理")
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final StreamBridge streamBridge;
    private final INotificationService notificationService;

    @Operation(summary = "发送通知 (异步)")
    @PostMapping("/send")
    public Result<NotificationResponse> send(@RequestBody NotificationRequest request) {
        // 投递至 MQ
        streamBridge.send("notificationConsumer-out-0", request);
        
        NotificationResponse response = new NotificationResponse();
        response.setStatus("ASYNC_SENT");
        return Result.success(response);
    }

    @Operation(summary = "查询通知历史")
    @GetMapping("/history")
    public Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        List<Notification> history = notificationService.getHistory(userId, status, type);
        
        List<NotificationHistoryResponse> responses = history.stream().map(n -> {
            NotificationHistoryResponse r = new NotificationHistoryResponse();
            r.setId(n.getId());
            r.setType(n.getType());
            r.setTitle(n.getTitle());
            r.setContent(n.getContent());
            r.setStatus(n.getStatus());
            r.setCreateTime(n.getCreateTime());
            return r;
        }).collect(Collectors.toList());
        
        return Result.success(responses);
    }

    @Operation(summary = "标记为已读")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id);
        return Result.success();
    }
}
```

- [ ] **Step 2: Update `NotificationConsumer` to persist records**
```java
// Modify the consumer bean in NotificationConsumer.java
@Bean
public Consumer<String> notificationConsumer() {
    return message -> {
        log.info("接收到通知消息: {}", message);
        try {
            JsonNode node = objectMapper.readTree(message);
            String type = node.get("type").asText();
            String target = node.get("target").asText();
            String content = node.get("content").asText();
            String title = node.has("title") ? node.get("title").asText() : "系统通知";
            Long userId = node.get("userId").asLong();

            if ("email".equalsIgnoreCase(type)) {
                String subject = node.has("subject") ? node.get("subject").asText() : title;
                notificationService.sendEmail(target, subject, content);
            } else if ("sms".equalsIgnoreCase(type)) {
                notificationService.sendSms(target, content);
            }
            
            // PERSISTENCE: Save to DB after sending
            notificationService.saveNotification(userId, type, title, content);
            
        } catch (Exception e) {
            log.error("处理通知失败: {}", message, e);
        }
    };
}
```

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/controller/ 
        cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/mq/
git commit -m "feat(notification): implement controller and MQ persistence"
```

### Task 7: Integration Testing
**Files:**
- Create: `cloud-infra-modules/notification-service/src/test/java/com/cloudinfra/notification/NotificationServiceIntegrationTest.java`

- [ ] **Step 1: Write integration test**
```java
package com.cloudinfra.notification;

import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class NotificationServiceIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    public void testFullNotificationFlow() throws InterruptedException {
        // 1. Send Notification
        NotificationRequest request = new NotificationRequest();
        request.setUserId(1L);
        request.setType("SYSTEM");
        request.setTitle("Test Title");
        request.setContent("Test Content");
        request.setTarget("test@example.com");

        ResponseEntity<Result> response = restTemplate.postForEntity("/notification/send", request, Result.class);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMsg()).isNotNull();

        // 2. Wait for MQ processing
        Thread.sleep(2000);

        // 3. Verify DB persistence
        List<Notification> history = notificationRepository.findHistory(1L, null, null);
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).getTitle()).isEqualTo("Test Title");

        // 4. Test Mark as Read
        Long id = history.get(0).getId();
        restTemplate.exchange("/notification/read/" + id, org.springframework.http.HttpMethod.PUT, null, Result.class);
        
        Notification updated = notificationRepository.getById(id);
        assertThat(updated.getStatus()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify**
Run: `mvn test -Dtest=NotificationServiceIntegrationTest`
Expected: PASS

- [ ] **Step 3: Final Commit**
```bash
git add cloud-infra-modules/notification-service/src/test/java/com/cloudinfra/notification/
git commit -m "test(notification): add full flow integration test"
```
