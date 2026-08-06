package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.service.AuditAggregatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 统一审计控制器
 */
@RestController
@RequestMapping("/system/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditAggregatorService auditAggregatorService;

    /**
     * 获取用户全链路行为时间线
     */
    @PreAuthorize("@ss.hasPermi('system:audit:list')")
    @GetMapping("/timeline")
    public List<UnifiedAuditRecord> getTimeline(
            @RequestParam Long userId) {
        return auditAggregatorService.getUnifiedTimeline(userId);
    }
}