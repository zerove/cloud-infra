package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.SystemException;
import com.cloudinfra.system.plugin.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    @PreAuthorize("@ss.hasPermi('system:ai:chat')")
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
            // 带上 cause: 反射调用失败的原因(签名不匹配/实现缺失)才是排查所需, 只留 message 等于丢失
            throw new SystemException("AI 响应异常: " + e.getMessage(), e);
        }
    }
}
