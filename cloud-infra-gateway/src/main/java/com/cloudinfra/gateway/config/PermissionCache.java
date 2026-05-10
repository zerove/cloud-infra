package com.cloudinfra.gateway.config;

import com.alibaba.nacos.api.config.annotation.NacosConfigListener;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class PermissionCache {
    private List<Map<String, Object>> permissions;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @NacosConfigListener(dataId = "gateway-permissions.json", groupId = "DEFAULT_GROUP")
    public void onChange(String newConfig) {
        this.permissions = (List<Map<String, Object>>) (List) JSONUtil.toList(newConfig, Map.class);
    }
    
    public List<Map<String, Object>> getPermissions() { return permissions; }
}
