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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkflowStepHandler 集成测试 (M6-04 Phase B)
 *
 * 覆盖 4 个关键场景:
 * - handlerInvoker 真正调用 handler.execute()
 * - 未注册 handler 抛 IllegalArgumentException
 * - handler 抛出业务异常 → engine 重试
 * - handler 输出保存到 variables
 */
class WorkflowStepHandlerIntegrationTest {

    private WorkflowEngine engine;
    private TestHandler testHandler;

    @BeforeEach
    void setUp() {
        testHandler = new TestHandler();
        // 注入 handlerInvoker 模拟 WorkflowStepHandlerRegistry
        engine = new WorkflowEngine(
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "test-workflow");
                t.setDaemon(true);
                return t;
            }),
            (handler, ctx) -> {
                if ("TestHandler.run".equals(handler)) {
                    return testHandler.execute(
                        new WorkflowStep("step-1", "plugin", List.of(), handler,
                            Map.of(), null, null, null),
                        ctx
                    );
                }
                throw new IllegalArgumentException("未注册 handler: " + handler);
            }
        );
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // ========== 场景 1: handler 真正调用 ==========

    @Test
    @DisplayName("handlerInvoker 真正调用 handler.execute() 返回业务结果")
    void shouldInvokeHandler() {
        WorkflowStep step = new WorkflowStep(
            "step-1", "plugin", List.of(),
            "TestHandler.run", Map.of(), null, null, null
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(step), null, 60);

        WorkflowResult result = engine.execute(workflow,
            new WorkflowContext(Map.of("input", "hello")));

        assertThat(result.success()).isTrue();
        assertThat(result.stepOutputs().get("step-1")).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.stepOutputs().get("step-1");
        assertThat(output.get("echo")).isEqualTo("hello");
        assertThat(testHandler.callCount).isEqualTo(1);
    }

    // ========== 场景 2: 未注册 handler 抛错 ==========

    @Test
    @DisplayName("未注册 handler → stepErrors 包含异常, success=false")
    void shouldMarkErrorForUnknownHandler() {
        WorkflowStep step = new WorkflowStep(
            "step-1", "plugin", List.of(),
            "UnknownHandler.run", Map.of(), null, null, null
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(step), null, 60);

        WorkflowResult result = engine.execute(workflow, new WorkflowContext(Map.of()));

        // engine 捕获异常为步骤失败, 不向上抛
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsKey("step-1");
    }

    // ========== 场景 3: handler 业务异常 + 重试 ==========

    @Test
    @DisplayName("handler 业务异常 → engine 触发重试")
    void shouldRetryOnHandlerException() {
        testHandler.failOnFirstNCalls = 2;  // 前 2 次抛异常, 第 3 次成功
        WorkflowStep step = new WorkflowStep(
            "step-1", "plugin", List.of(),
            "TestHandler.run",
            Map.of(),
            new WorkflowStep.RetryConfig(3, "10ms"),  // 3 次重试
            null, null
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(step), null, 60);

        WorkflowResult result = engine.execute(workflow, new WorkflowContext(Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(testHandler.callCount).isEqualTo(3);  // 3 次调用: 2 失败 + 1 成功
    }

    // ========== 场景 4: handler 输出保存 ==========

    @Test
    @DisplayName("handler 输出保存到 stepOutputs 供后续步骤引用")
    void shouldStoreHandlerOutput() {
        testHandler.nextOutput = Map.of("data", "value-1", "count", 42);
        WorkflowStep step = new WorkflowStep(
            "step-1", "plugin", List.of(),
            "TestHandler.run", Map.of(), null, null, null
        );
        Workflow workflow = new Workflow("test", "1.0", List.of(step), null, 60);

        WorkflowResult result = engine.execute(workflow, new WorkflowContext(Map.of()));

        assertThat(result.stepOutputs()).containsKey("step-1");
        assertThat(result.stepOutputs().get("step-1")).isEqualTo(testHandler.nextOutput);
    }

    // ========== 测试 handler ==========

    static class TestHandler {
        int callCount = 0;
        int failOnFirstNCalls = 0;
        Object nextOutput = null;

        Object execute(WorkflowStep step, WorkflowContext ctx) {
            callCount++;
            if (callCount <= failOnFirstNCalls) {
                throw new RuntimeException("业务异常 #" + callCount);
            }
            if (nextOutput != null) {
                return nextOutput;
            }
            // 用 HashMap 避免 null 值 (Map.of 不支持)
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("echo", ctx.get("input"));
            result.put("step", step.id());
            return result;
        }
    }
}