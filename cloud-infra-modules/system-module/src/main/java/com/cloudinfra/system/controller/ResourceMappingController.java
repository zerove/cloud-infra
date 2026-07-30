package com.cloudinfra.system.controller;

import com.cloudinfra.common.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.service.ResourceMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 资源拓扑映射控制器
 */
@RestController
@RequestMapping("/system/resource-mapping")
@RequiredArgsConstructor
public class ResourceMappingController {

    private final ResourceMappingService resourceMappingService;

    /**
     * 获取资源的有效访问节点集合 (包含拓扑展开后的结果)
     */
    @GetMapping("/effective-nodes")
    public Set<Long> getEffectiveNodes(
            @RequestParam String resourceId,
            @RequestParam String resourceType) {
        return resourceMappingService.getEffectiveAccessNodes(resourceId, resourceType);
    }

    /**
     * 绑定资源到节点
     */
    @AuditLog(module = "RESOURCE", action = "BIND")
    @PostMapping("/bind")
    public Result<Void> bindResource(
            @RequestParam String resourceId,
            @RequestParam String resourceType,
            @RequestParam Long nodeId,
            @RequestParam String nodeType,
            @RequestParam String relationType) {
        resourceMappingService.bindResource(resourceId, resourceType, nodeId, nodeType, relationType);
        return Result.success();
    }
}