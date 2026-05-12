# Search Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a high-performance operation log search service using Elasticsearch with multi-field comprehensive search and pagination.

**Architecture:** Controller -> Service -> Repository -> Elasticsearch. Uses `multi_match` query for comprehensive search across title, operator, method, and URL.

**Tech Stack:** Spring Boot 4, JDK 25, Spring Data Elasticsearch, OpenFeign.

---

## Phase 1: Repository & Data Access

### Task 1: Enhance OperLogRepository for Keyword Search
**Files:**
- Modify: `cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/repository/OperLogRepository.java`

- [ ] **Step 1: Add paginated keyword search method**
```java
package com.cloudinfra.search.repository;

import com.cloudinfra.search.domain.OperLogDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface OperLogRepository extends ElasticsearchRepository<OperLogDocument, Long> {
    
    @Query("{"bool": {"should": [{"match": {"title": "?0"}}, {"match": {"operName": "?0"}}, {"match": {"method": "?0"}}, {"match": {"operUrl": "?0"}}]}}")
    Page<OperLogDocument> findByKeyword(String keyword, Pageable pageable);
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/repository/OperLogRepository.java
git commit -m "feat(search): add paginated multi-field keyword search to repository"
```

---

## Phase 2: Service Layer Implementation

### Task 2: Implement SearchService
**Files:**
- Create: `cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/service/SearchService.java`
- Create: `cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/service/impl/SearchServiceImpl.java`

- [ ] **Step 1: Define `SearchService` interface**
```java
package com.cloudinfra.search.service;

import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.api.search.dto.OperLogDTO;

public interface SearchService {
    SearchResponse<OperLogDTO> searchOperLog(SearchRequest request);
}
```

- [ ] **Step 2: Implement `SearchServiceImpl`**
```java
package com.cloudinfra.search.service.impl;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final OperLogRepository operLogRepository;

    @Override
    public SearchResponse<OperLogDTO> searchOperLog(SearchRequest request) {
        log.info("Executing operlog search: keyword={}, page={}, size={}", request.getKeyword(), request.getPage(), request.getSize());
        
        // Page in SearchRequest is 1-based, PageRequest is 0-based
        int page = (request.getPage() != null && request.getPage() > 0) ? request.getPage() - 1 : 0;
        int size = (request.getSize() != null && request.getSize() > 0) ? request.getSize() : 10;
        Pageable pageable = PageRequest.of(page, size);
        
        Page<OperLogDocument> pageResult = operLogRepository.findByKeyword(request.getKeyword(), pageable);
        
        SearchResponse<OperLogDTO> response = new SearchResponse<>();
        response.setTotal(pageResult.getTotalElements());
        response.setData(pageResult.getContent().stream()
            .map(this::convertToDto)
            .collect(Collectors.toList()));
            
        return response;
    }

    private OperLogDTO convertToDto(OperLogDocument doc) {
        OperLogDTO dto = new OperLogDTO();
        dto.setId(doc.getOperId());
        dto.setTitle(doc.getTitle());
        dto.setUserName(doc.getOperName());
        dto.setCreateTime(doc.getOperTime());
        dto.setMethod(doc.getMethod());
        dto.setIp(doc.getOperIp());
        return dto;
    }
}
```

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/service/
git commit -m "feat(search): implement search service with pagination and multi-field query"
```

---

## Phase 3: Controller Layer Implementation

### Task 3: Implement SearchController
**Files:**
- Create: `cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/controller/SearchController.java`

- [ ] **Step 1: Implement `SearchController`**
```java
package com.cloudinfra.search.controller;

import com.cloudinfra.api.search.*;
import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

@Tag(name = "搜索管理")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController implements SearchApi {

    private final SearchService searchService;

    @Override
    @Operation(summary = "搜索操作日志")
    @GetMapping("/operlog")
    public Result<SearchResponse<OperLogDTO>> searchOperLog(@SpringQueryMap SearchRequest request) {
        return Result.success(searchService.searchOperLog(request));
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/controller/SearchController.java
git commit -m "feat(search): implement search controller implementing SearchApi"
```

---

## Phase 4: Integration Testing

### Task 4: Implement Integration Test
**Files:**
- Create: `cloud-infra-modules/search-service/src/test/java/com/cloudinfra/search/SearchServiceIntegrationTest.java`

- [ ] **Step 1: Write integration test**
```java
package com.cloudinfra.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.common.core.domain.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.List;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SearchServiceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OperLogRepository operLogRepository;

    @Test
    public void testMultiFieldSearch() {
        // 1. Prepare data
        OperLogDocument doc1 = new OperLogDocument();
        doc1.setOperId(1L);
        doc1.setTitle("用户管理-创建用户");
        doc1.setOperName("管理员");
        doc1.setMethod("POST");
        doc1.setOperUrl("/sys/user/add");
        doc1.setOperTime(LocalDateTime.now());
        operLogRepository.save(doc1);

        OperLogDocument doc2 = new OperLogDocument();
        doc2.setOperId(2L);
        doc2.setTitle("岗位管理-修改岗位");
        doc2.setOperName("操作员");
        doc2.setMethod("PUT");
        doc2.setOperUrl("/sys/post/update");
        doc2.setOperTime(LocalDateTime.now());
        operLogRepository.save(doc2);

        // 2. Execute search on title
        SearchRequest request = new SearchRequest();
        request.setKeyword("用户");
        request.setPage(1);
        request.setSize(10);

        webTestClient.get().uri("/search/operlog?keyword=用户&page=1&size=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Result.class)
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                // Verify total count and data list
            });
    }

    @Test
    public void testPagination() {
        // 1. Prepare data
        for (int i = 0; i < 15; i++) {
            OperLogDocument doc = new OperLogDocument();
            doc.setOperId((long)i);
            doc.setTitle("Test Log " + i);
            doc.setOperName("User" + i);
            doc.setOperTime(LocalDateTime.now());
            operLogRepository.save(doc);
        }

        // 2. Test page 1
        SearchRequest request = new SearchRequest();
        request.setKeyword("Test");
        request.setPage(1);
        request.setSize(10);

        webTestClient.get().uri("/search/operlog?keyword=Test&page=1&size=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Result.class)
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
            });

        // 3. Test page 2
        webTestClient.get().uri("/search/operlog?keyword=Test&page=2&size=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Result.class)
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
            });
    }
}
```

- [ ] **Step 2: Run tests and verify**
Run: `mvn test -Dtest=SearchServiceIntegrationTest`
Expected: PASS

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-modules/search-service/src/test/java/com/cloudinfra/search/SearchServiceIntegrationTest.java
git commit -m "test(search): add integration tests for multi-field search and pagination"
```
