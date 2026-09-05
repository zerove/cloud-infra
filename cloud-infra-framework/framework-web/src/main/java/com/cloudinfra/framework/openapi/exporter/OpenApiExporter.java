package com.cloudinfra.framework.openapi.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OpenAPI 导出器 (M6-05 Phase B)
 *
 * Spring Boot 启动完成后, 将 OpenAPI 文档 dump 到
 * {@code target/openapi/{module}.json} (默认) 或 {@code cloud-infra.openapi.export-path} 配置路径.
 *
 * 用法:
 * - 加 {@code @EnableOpenApi} 注解后自动注册 (需 OpenApiAutoConfiguration 装配 OpenAPI Bean)
 * - 或直接 {@code @Component} 注入, 默认会找 Spring 容器中所有 OpenAPI Bean
 *
 * 设计:
 * - 启动后 5s 延迟 (确保所有 Controller 注册完成, springdoc 已生成完整 OpenAPI)
 * - 单线程同步导出, 避免阻塞启动
 * - 失败仅 warn log, 不阻塞应用启动
 *
 * 后续 Phase C: 聚合脚本 build-openapi.sh 拉各模块 openapi.json 合并
 */
@Component
@ConditionalOnProperty(prefix = "cloud-infra.openapi", name = "export-enabled", havingValue = "true", matchIfMissing = false)
public class OpenApiExporter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiExporter.class);

    private final OpenAPI openApi;
    private final ObjectMapper objectMapper;
    private final Environment env;
    private final String exportPath;

    public OpenApiExporter(OpenAPI openApi,
                           ObjectMapper objectMapper,
                           Environment env,
                           @Value("${cloud-infra.openapi.export-path:target/openapi}") String exportPath) {
        this.openApi = openApi;
        this.objectMapper = objectMapper;
        this.env = env;
        this.exportPath = exportPath;
    }

    /**
     * Spring Boot 启动完成事件: 延迟 5s 后导出, 等待所有 Controller 注册
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 延迟 5s 确保 springdoc 完成 OpenAPI 生成
        Thread exporter = new Thread(this::exportWithRetry, "openapi-exporter");
        exporter.setDaemon(true);
        exporter.start();
    }

    /**
     * 导出 OpenAPI 到 JSON 文件 (带重试)
     */
    void exportWithRetry() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                export();
                return;
            } catch (Exception e) {
                log.warn("[OpenApiExporter] 导出失败 (尝试 {}/3): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("[OpenApiExporter] 3 次重试均失败, 跳过本次导出");
    }

    /**
     * 实际导出 (单次尝试, 失败抛错由 exportWithRetry 处理)
     */
    void export() throws java.io.IOException {
        String moduleName = env.getProperty("spring.application.name", "cloud-infra-module");
        Path targetPath = Paths.get(exportPath, moduleName + ".json");
        Files.createDirectories(targetPath.getParent());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(openApi);
        Files.writeString(targetPath, json);
        log.info("[OpenApiExporter] 导出 OpenAPI: path={}, size={} bytes",
            targetPath, json.length());
    }
}