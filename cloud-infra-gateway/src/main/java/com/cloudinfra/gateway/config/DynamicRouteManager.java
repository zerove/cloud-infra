package com.cloudinfra.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 网关动态路由管理器：监听 Nacos 配置，实时更新本地内存中的路由表
 */
@Component
public class DynamicRouteManager {

    private static final Logger log = LoggerFactory.getLogger(
        DynamicRouteManager.class
    );

    private static final String DATA_ID = "gateway-routes.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    private final ConfigService configService;
    private final Map<String, Object> routeMap = new ConcurrentHashMap<>();

    public DynamicRouteManager(NacosConfigManager nacosConfigManager) {
        try {
            this.configService = nacosConfigManager.getConfigService();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Nacos ConfigService", e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            String configInfo = configService.getConfig(DATA_ID, GROUP, 5000L);
            updateRoutes(configInfo);

            configService.addListener(
                DATA_ID,
                GROUP,
                new AbstractListener() {
                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        log.info("检测到路由配置变更，开始更新...");
                        updateRoutes(configInfo);
                    }
                }
            );
        } catch (Exception e) {
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
