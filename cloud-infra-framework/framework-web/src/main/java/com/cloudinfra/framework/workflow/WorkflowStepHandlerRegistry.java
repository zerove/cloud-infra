package com.cloudinfra.framework.workflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WorkflowStepHandler 注册表 (M6-04 Phase B)
 *
 * 启动时扫描所有 {@link WorkflowStepHandler} Bean, 按 "BeanName.methodName" 建立索引.
 * WorkflowEngine 通过此注册表查找 handler.
 *
 * 用法:
 * <pre>
 * {@code
 * @Component("OrderPlugin")
 * public class OrderPlugin implements WorkflowStepHandler {
 *     @Override public String methodName() { return "create"; }
 *     // handler = "OrderPlugin.create"
 * }
 * }
 * </pre>
 *
 * 集成方式:
 * - 实现 WorkflowStepHandler 接口 + 标注 @Component
 * - 注册表启动自动发现, 无需显式注册
 */
@Component
public class WorkflowStepHandlerRegistry {

    private final Map<String, WorkflowStepHandler> handlers = new HashMap<>();

    @Autowired
    public WorkflowStepHandlerRegistry(ApplicationContext applicationContext) {
        // 扫描所有 WorkflowStepHandler Bean
        Map<String, WorkflowStepHandler> beans = applicationContext.getBeansOfType(WorkflowStepHandler.class);
        for (Map.Entry<String, WorkflowStepHandler> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            WorkflowStepHandler handler = entry.getValue();
            String key = beanName + "." + handler.methodName();
            handlers.put(key, handler);
        }
    }

    /**
     * 通过 handler 字符串查找处理器.
     * @param handler "BeanName.methodName"
     * @return Optional<WorkflowStepHandler>
     */
    public Optional<WorkflowStepHandler> find(String handler) {
        if (handler == null || handler.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(handler));
    }

    /**
     * 返回所有已注册的 handler 列表 (调试用).
     */
    public List<String> list() {
        return handlers.keySet().stream().sorted().toList();
    }
}