package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.SystemException;
import com.cloudinfra.system.plugin.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * AI 智能助手控制器
 */
@RestController
@RequestMapping("/system/ai")
@RequiredArgsConstructor
public class AiController {

    private final PluginRegistry pluginRegistry;

    /**
     * AI 智能问答接口
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String query) {
        // 从插件注册表获取 AI 助手服务
        Object assistant = pluginRegistry.getService("ai.assistant.chat");
        if (assistant == null) {
            throw new BusinessException("AI 助手插件未启用");
        }

        try {
            // 动态调用 chat 方法 (模拟)
            java.lang.reflect.Method method = assistant.getClass().getInterfaces()[0].getMethod("chat", String.class);
            return (String) method.invoke(assistant, query);
        } catch (Exception e) {
            throw new SystemException("AI 响应异常: " + e.getMessage());
        }
    }
}