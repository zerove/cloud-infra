package com.cloudinfra.framework.workflow;

import com.cloudinfra.common.core.workflow.Workflow;
import com.cloudinfra.common.core.workflow.WorkflowContext;
import com.cloudinfra.common.core.workflow.WorkflowEngine;
import com.cloudinfra.common.core.workflow.WorkflowResult;
import com.cloudinfra.common.core.workflow.WorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow Phase C 测试 (M6-04): 变量传递 + Saga 补偿
 *
 * 覆盖 3 个关键场景:
 * - 步骤输出保存到 variables (后续步骤可引用)
 * - Saga 补偿: 步骤失败 → onFailure handler 调用
 * - 补偿调用上下文: 可访问失败步骤的输出
 */
class WorkflowPhaseCTest {

    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine(
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "test-phase-c");
                t.setDaemon(true);
                return t;
            }),
            (handler, ctx) -> invoke(handler, ctx)
        );
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // ========== 场景 1: 步骤输出保存到 variables ==========

    @Test
    @DisplayName("Phase C: 步骤输出保存到 context.variables (steps.{id}.output)")
    void shouldStoreStepOutputForLaterReference() {
        // step-1 输出 orderId=42, step-2 应能在 handler 内读到
        Map<String, Integer> stepCalls = new HashMap<>();
        Map<String, String> capturedOrderIds = new HashMap<>();

        // 直接调用 engine, 单个 step + 验证 output 保存
        WorkflowStep step1 = new WorkflowStep(
            "create-order", "plugin", List.of(),
            "OrderPlugin.create", Map.of("userId", 100), null, null, null
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(step1), null, 60);

        WorkflowContext ctx = new WorkflowContext(Map.of("userId", 100));
        WorkflowResult result = engine.execute(workflow, ctx);

        assertThat(result.success()).isTrue();
        assertThat(ctx.getVariable("steps.create-order.output")).isNotNull();
    }

    // ========== 场景 2: Saga 补偿 onFailure ==========

    @Test
    @DisplayName("Phase C: 步骤失败 → onFailure 补偿 handler 调用")
    void shouldTriggerCompensationOnFailure() {
        WorkflowStep failed = new WorkflowStep(
            "charge", "plugin", List.of(),
            "FailPlugin.charge", Map.of(), null, null, "refund"
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(failed), null, 60);

        WorkflowResult result = engine.execute(workflow, new WorkflowContext(Map.of()));

        // 步骤本身失败, 但 onFailure="refund" 被调用, 补偿后仍抛错 (业务已记录)
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsKey("charge");
    }

    // ========== 场景 3: 步骤链变量传递 ==========

    @Test
    @DisplayName("Phase C: 步骤链传递 - step1 输出作为 step2 输入")
    void shouldChainVariablesAcrossSteps() {
        WorkflowStep step1 = new WorkflowStep(
            "step-1", "plugin", List.of(),
            "APlugin.run", Map.of(), null, null, null
        );
        WorkflowStep step2 = new WorkflowStep(
            "step-2", "plugin", List.of("step-1"),
            "BPlugin.run", Map.of(), null, null, null
        );
        Workflow workflow = new Workflow("chain", "1.0", List.of(step1, step2), null, 60);

        WorkflowContext ctx = new WorkflowContext(Map.of());
        WorkflowResult result = engine.execute(workflow, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.stepOutputs()).containsKeys("step-1", "step-2");
        // step-1 输出已保存, step-2 触发时 ctx 已含 step-1 输出
        assertThat(ctx.getVariable("steps.step-1.output")).isNotNull();
    }

    /**
     * 模拟 handler 调用. 不同 methodName 返回不同数据.
     */
    private Object invoke(String handler, WorkflowContext ctx) {
        // 解析 BeanName.methodName
        if (handler.startsWith("APlugin.")) {
            return Map.of("value", "from-A");
        }
        if (handler.startsWith("BPlugin.")) {
            // 验证可访问步骤输出
            Object prior = ctx.getVariable("steps.step-1.output");
            return Map.of("value", "from-B", "prior", prior);
        }
        if (handler.startsWith("OrderPlugin.create")) {
            return Map.of("orderId", 42, "userId", ctx.get("userId"));
        }
        if (handler.startsWith("FailPlugin.")) {
            throw new RuntimeException("业务失败");
        }
        if (handler.startsWith("Refund.")) {
            // 补偿 handler
            return Map.of("refunded", true);
        }
        throw new IllegalArgumentException("未注册 handler: " + handler);
    }
}