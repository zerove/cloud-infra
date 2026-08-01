package com.cloudinfra.gateway.config;

import com.alibaba.nacos.api.config.annotation.NacosConfigListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 网关权限缓存
 * 使用 Jackson 替代 hutool JSONUtil，减少运行期反射依赖（Native Image 友好）
 * ObjectMapper 线程安全，使用静态实例避免依赖注入上下文约束
 */
@Slf4j
@Component
public class PermissionCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private volatile List<Map<String, Object>> permissions;

    @NacosConfigListener(dataId = "gateway-permissions.json", groupId = "DEFAULT_GROUP")
    public void onChange(String newConfig) {
        try {
            this.permissions = OBJECT_MAPPER.readValue(newConfig, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("解析网关权限配置失败", e);
        }
    }

    public List<Map<String, Object>> getPermissions() { return permissions; }
}
