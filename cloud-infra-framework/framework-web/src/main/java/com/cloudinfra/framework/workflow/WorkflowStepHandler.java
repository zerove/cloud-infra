package com.cloudinfra.framework.workflow;

import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.common.core.workflow.WorkflowContext;
import com.cloudinfra.common.core.workflow.WorkflowStep;

import java.util.Map;

/**
 * 业务编排步骤处理器接口 (M6-04 Phase B)
 *
 * 与 {@link com.cloudinfra.framework.plugin.IServicePlugin} 集成:
 * - 业务插件实现此接口即可作为 workflow 步骤被编排引擎调用
 * - handler 字符串格式: "PluginBeanName.methodName" (e.g. "OrderPlugin.create")
 *
 * 上下文:
 * - {@link WorkflowStep} 提供步骤定义 (handler/args/retry/timeout)
 * - {@link WorkflowContext} 提供 trigger 数据 + 跨步骤共享变量
 * - 可选从 {@link TenantContext} 读取当前租户 (M6-01 多租户隔离)
 *
 * 异常:
 * - 业务异常应抛 RuntimeException, engine 捕获后触发重试 + 失败传播
 * - Saga 补偿 (onFailure) 由 Phase C 通过 workflow variables 传递
 *
 * 用法:
 * <pre>
 * {@code
 * @Component("OrderPlugin")
 * public class OrderPlugin implements WorkflowStepHandler {
 *     @Override
 *     public String methodName() { return "create"; }
 *
 *     @Override
 *     public Object execute(WorkflowStep step, WorkflowContext ctx) {
 *         Map<String, Object> args = step.args();
 *         Long userId = ((Number) ctx.get("userId")).longValue();
 *         // 业务逻辑...
 *         return Map.of("orderId", orderId);
 *     }
 * }
 * }
 * </pre>
 */
public interface WorkflowStepHandler {

    /**
     * 返回当前实现支持的 method 名 (用于 handler 字符串查找: "BeanName.methodName")
     * 例如 OrderPlugin 的 methodName() 返回 "create" → 完整 handler = "OrderPlugin.create"
     */
    String methodName();

    /**
     * 执行步骤. 返回值会保存到 WorkflowContext.variables, 后续步骤可通过
     * ctx.getVariable(stepId) 引用 (M6-04 Phase B 实现).
     *
     * @param step 步骤定义
     * @param ctx workflow 上下文 (trigger + variables)
     * @return 步骤输出 (保存为 variables[step.id()])
     */
    Object execute(WorkflowStep step, WorkflowContext ctx);

    /**
     * Saga 补偿: 步骤失败时反向执行 (Phase C 完整实现).
     * 默认空实现 (no-op).
     */
    default Object compensate(WorkflowStep step, WorkflowContext ctx, Throwable cause) {
        return null;
    }
}