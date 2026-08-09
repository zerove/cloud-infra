package com.cloudinfra.system.plugin.impl;

import com.cloudinfra.framework.plugin.IServicePlugin;
import com.cloudinfra.framework.plugin.PluginContext;
import com.cloudinfra.framework.plugin.PluginMetadata;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 媒体 AI 自动打标签插件
 * 演示如何通过插件为媒体中心增加 AI 能力
 */
@Slf4j
public class MediaAiTaggingPlugin implements IServicePlugin {

    // 定义一个简单的资源记录，避免直接依赖 media-module 的 Domain
    public record MediaResource(String title, String type) {}

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata(
            "media-ai-tagger",
            "媒体 AI 自动打标签插件",
            "1.0.0",
            "利用 AI 分析媒体内容并自动生成关键词标签",
            Map.of("category", "intelligence")
        );
    }

    @Override
    public void onEnable(PluginContext context) {
        log.info("AI 打标签插件启动中...");
        
        // 注册一个 AI 打标签服务
        context.registerService("media.ai.tagger", (AiTagger) resource -> {
            log.info("AI 正在分析资源: {}", resource.title());
            
            // 模拟 AI 分析逻辑
            List<String> tags = new ArrayList<>();
            String title = resource.title().toLowerCase();
            
            if (title.contains("nature") || title.contains("forest")) {
                tags.add("自然");
            }
            if (title.contains("tech") || title.contains("code")) {
                tags.add("技术");
            }
            if (title.contains("tutorial")) {
                tags.add("教程");
            }
            
            // 根据媒体类型增加通用标签
            if (resource.type() != null) {
                tags.add("格式:" + resource.type());
            }
            
            if (tags.isEmpty()) {
                tags.add("通用内容");
            }
            
            return tags;
        });
        
        log.info("AI 打标签服务已注册到底座！");
    }

    @Override
    public void onDisable() {
        log.info("AI 打标签插件已停止");
    }

    /**
     * 定义插件提供的服务接口
     */
    public interface AiTagger {
        List<String> generateTags(MediaResource resource);
    }
}
