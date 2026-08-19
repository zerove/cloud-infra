package com.cloudinfra.api.system.fallback;

import com.cloudinfra.api.system.ResourceMappingApi;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * ResourceMappingApi 降级工厂
 */
@Component
public class ResourceMappingApiFallbackFactory implements FallbackFactory<ResourceMappingApi> {

    private static final Logger log = LoggerFactory.getLogger(ResourceMappingApiFallbackFactory.class);

    @Override
    public ResourceMappingApi create(Throwable cause) {
        log.warn("ResourceMappingApi fallback triggered: {}", cause.getMessage());
        return new ResourceMappingApi() {
            @Override
            public Result<Set<Long>> getEffectiveNodes(String resourceId, String resourceType) {
                return Result.success(Collections.emptySet());
            }

            @Override
            public Result<Void> bindResource(String resourceId, String resourceType, Long nodeId,
                    String nodeType, String relationType) {
                return Result.fail(503, "系统服务暂不可用");
            }
        };
    }
}