package com.cloudinfra.api.system;

import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@FeignClient(name = "system-module")
public interface ResourceMappingApi {
    /**
     * 获取资源的有效访问节点集合 (包含拓扑展开后的结果)
     */
    @GetMapping("/system/resource-mapping/effective-nodes")
    Result<Set<Long>> getEffectiveNodes(@RequestParam String resourceId, @RequestParam String resourceType);

    /**
     * 绑定资源到节点
     */
    @PostMapping("/system/resource-mapping/bind")
    Result<Void> bindResource(
            @RequestParam String resourceId,
            @RequestParam String resourceType,
            @RequestParam Long nodeId,
            @RequestParam String nodeType,
            @RequestParam String relationType);
}