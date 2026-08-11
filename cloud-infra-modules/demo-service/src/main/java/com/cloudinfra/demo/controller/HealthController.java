package com.cloudinfra.demo.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.security.PlatformPrincipal;
import com.cloudinfra.common.core.security.PlatformSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查与平台能力示例
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
            "status", "UP",
            "service", "demo-service",
            "authenticated", PlatformSecurityContext.isAuthenticated()
        ));
    }

    @GetMapping("/whoami")
    public Result<PlatformPrincipal> whoami() {
        return Result.success(PlatformSecurityContext.current());
    }
}
