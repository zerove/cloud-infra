package com.cloudinfra.common.core.workflow;

import java.util.Map;

/**
 * Workflow 执行上下文 (M6-04)
 *
 * 包含 trigger 数据 (用户传入) + workflow 共享变量 (步骤间传递).
 *
 * 用法:
 * <pre>
 * {@code
 * WorkflowContext context = new WorkflowContext(Map.of(
 *     "userId", 42,
 *     "orderItems", List.of(...)
 * ));
 * }
 * </pre>
 */
public record WorkflowContext(
    Map<String, Object> trigger,    // 触发器数据 (workflow 入口)
    Map<String, Object> variables  // workflow 共享变量 (步骤间传递)
) {
    public WorkflowContext {
        if (trigger == null) {
            trigger = Map.of();
        }
        if (variables == null) {
            variables = new java.util.HashMap<>();
        }
    }

    public WorkflowContext(Map<String, Object> trigger) {
        this(trigger, new java.util.HashMap<>());
    }

    public Object get(String key) {
        Object v = variables.get(key);
        return v != null ? v : trigger.get(key);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }
}