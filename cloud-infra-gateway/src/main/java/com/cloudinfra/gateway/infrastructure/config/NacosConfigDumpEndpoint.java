package com.cloudinfra.gateway.infrastructure.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.Resource;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Nacos 配置转储端点
 * 
 * 用于运维排查配置漂移：对比 Nacos 实际配置与 deploy/config/*.yaml 源文件
 * 
 * 访问：
 *   GET /management/config-dump          - 获取预定义关键配置列表
 *   GET /management/config-dump/{dataId} - 获取单个配置详情
 */
@Component
@Endpoint(id = "config-dump")
public class NacosConfigDumpEndpoint {

    @Resource
    private ConfigService configService;

    /** 关键配置 dataId 列表（对应 deploy/config/*.yaml） */
    private static final List<String> KEY_CONFIGS = List.of(
        "gateway.yaml",
        "auth.yaml",
        "system-module.yaml",
        "file-service.yaml",
        "notification-service.yaml",
        "search-service.yaml",
        "actuator-config.yaml"
    );

    /**
     * 获取所有关键配置概览
     */
    @ReadOperation
    public Map<String, Object> dumpAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configs", new ArrayList<>());
        
        List<Map<String, Object>> configs = (List<Map<String, Object>>) result.get("configs");
        
        for (String dataId : KEY_CONFIGS) {
            try {
                String content = configService.getConfig(dataId, "DEFAULT_GROUP", 5000L);
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("dataId", dataId);
                cfg.put("group", "DEFAULT_GROUP");
                cfg.put("exists", content != null && !content.isEmpty());
                cfg.put("md5", content != null ? md5(content) : null);
                cfg.put("size", content != null ? content.length() : 0);
                configs.add(cfg);
            } catch (NacosException e) {
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("dataId", dataId);
                cfg.put("group", "DEFAULT_GROUP");
                cfg.put("exists", false);
                cfg.put("error", e.getErrMsg());
                configs.add(cfg);
            }
        }
        
        return result;
    }

    /**
     * 获取单个配置详情
     * @param selector dataId，如 "gateway.yaml"
     */
    @ReadOperation
    public Map<String, Object> dumpOne(@Selector String dataId) {
        try {
            String content = configService.getConfig(dataId, "DEFAULT_GROUP", 5000L);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataId", dataId);
            result.put("group", "DEFAULT_GROUP");
            result.put("content", content);
            result.put("md5", content != null ? md5(content) : null);
            result.put("size", content != null ? content.length() : 0);
            return result;
        } catch (NacosException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("dataId", dataId);
            error.put("group", "DEFAULT_GROUP");
            error.put("error", e.getErrMsg());
            error.put("code", e.getErrCode());
            return error;
        }
    }

    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "md5-error";
        }
    }
}