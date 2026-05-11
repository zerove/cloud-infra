package com.cloudinfra.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 网关动态路由管理器：监听 Nacos 配置，实时更新本地内存中的路由表
 */
@Slf4j
@Component
public class DynamicRouteManager {

    private static final String DATA_ID = "gateway-routes.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    private final NacosConfigManager nacosConfigManager;
    private final Map<String, Object> routeMap = new ConcurrentHashMap<>();

    public DynamicRouteManager(NacosConfigManager nacosConfigManager) {
        this.nacosConfigManager = nacosConfigManager;
    }

    @PostConstruct
    public void init() {
        try {
            String configInfo = nacosConfigManager.getConfigService().getConfig(DATA_ID, GROUP, 5000);
            updateRoutes(configInfo);

            nacosConfigManager.getConfigService().addListener(DATA_ID, GROUP, new Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("检测到路由配置变更，开始更新...");
                    updateRoutes(configInfo);
                }

                @Override
                public Executor getExecutor() {
                    return null;
                }
            });
        } catch (NacosException e) {
            log.error("Nacos 路由监听初始化失败", e);
        }
    }

    private void updateRoutes(String configInfo) {
        if (configInfo == null || configInfo.isEmpty()) return;
        Map<String, Object> newRoutes = new Yaml().load(configInfo);
        routeMap.clear();
        routeMap.putAll(newRoutes);
        log.info("路由表更新完成: {}", routeMap);
    }

    public Map<String, Object> getRoutes() {
        return routeMap;
    }
}
