package com.cloudinfra.common.core.workflow;

/**
 * 步骤执行失败 (M6-04)。
 *
 * <p>步骤在重试耗尽后仍失败时抛出, {@link #getCause()} 保留最后一次失败原因
 * (可能是业务异常, 也可能是 {@link WorkflowEngine.StepTimeoutException} 超时信号)。
 *
 * <p>引擎对外不直接抛裸 {@code RuntimeException}, 便于调用方按类型区分
 * "编排失败" 与 "引擎自身配置错误" (后者为 {@link IllegalArgumentException} /
 * {@link IllegalStateException})。
 */
public class WorkflowExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stepId;

    public WorkflowExecutionException(String stepId, int attempts, Throwable cause) {
        super("步骤 " + stepId + " 重试 " + attempts + " 次均失败", cause);
        this.stepId = stepId;
    }

    /** 失败步骤的 id。 */
    public String getStepId() {
        return stepId;
    }
}
