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

    /**
     * 优先读 variables, 否则读 trigger.
     */
    public Object get(String key) {
        Object v = variables.get(key);
        return v != null ? v : trigger.get(key);
    }

    /**
     * 仅读 workflow 共享变量 (不含 trigger).
     * 用于步骤间传递 (如 ${steps.create-order.output.orderId}).
     */
    public Object getVariable(String key) {
        return variables.get(key);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * 将步骤输出保存到 variables, 供后续步骤引用.
     */
    public void setStepOutput(String stepId, Object output) {
        variables.put("steps." + stepId + ".output", output);
    }
}