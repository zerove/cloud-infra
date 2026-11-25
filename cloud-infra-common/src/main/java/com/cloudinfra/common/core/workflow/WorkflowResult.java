package com.cloudinfra.common.core.workflow;

import java.util.Map;
import java.util.Set;

/**
 * Workflow 执行结果 (M6-04)
 *
 * 包含每步输出 + 失败信息 + 跳过步骤.
 *
 * 用法:
 * <pre>
 * {@code
 * WorkflowResult result = engine.execute(workflow, context);
 * if (!result.success()) {
 *     log.warn("失败步骤: {}", result.errors());
 *     log.warn("跳过步骤: {}", result.skippedSteps());
 * }
 * }
 * </pre>
 */
public record WorkflowResult(
    String workflowName,
    boolean success,
    Map<String, Object> stepOutputs,    // 步骤 id → 输出
    Map<String, Throwable> errors,     // 步骤 id → 异常 (失败步骤)
    Set<String> skippedSteps,          // 因依赖失败被跳过的步骤 id
    long durationMs                    // 总耗时
) {
}