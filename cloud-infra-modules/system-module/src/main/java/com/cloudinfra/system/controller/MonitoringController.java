package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.PlatformHealthSnapshot;
import com.cloudinfra.system.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    /**
     * 获取平台实时健康快照
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/snapshot")
    public PlatformHealthSnapshot getSnapshot() {
        return monitoringService.getHealthSnapshot();
    }
}