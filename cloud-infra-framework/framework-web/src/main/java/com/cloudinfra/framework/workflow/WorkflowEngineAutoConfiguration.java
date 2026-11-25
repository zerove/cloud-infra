package com.cloudinfra.framework.workflow;

import com.cloudinfra.common.core.workflow.WorkflowEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

/**
 * Workflow 引擎 Spring 集成配置 (M6-04 Phase B)
 *
 * 注册 {@link WorkflowEngine} Bean, 自动注入 {@link WorkflowStepHandlerRegistry} 作为 handlerInvoker.
 * 启用条件: classpath 存在 WorkflowStepHandlerRegistry (framework-web 已默认加载).
 */
@Configuration
@ConditionalOnBean(WorkflowStepHandlerRegistry.class)
public class WorkflowEngineAutoConfiguration {

    /**
     * WorkflowEngine Bean, 注入 registry 作为 handler 查找器.
     */
    @Bean(destroyMethod = "shutdown")
    public WorkflowEngine workflowEngine(WorkflowStepHandlerRegistry registry) {
        return new WorkflowEngine(
            Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "workflow-engine");
                t.setDaemon(true);
                return t;
            }),
            (handler, ctx) -> {
                // 通过 registry 查找 handler, 调用 execute
                var handlerOpt = registry.find(handler);
                if (handlerOpt.isEmpty()) {
                    throw new IllegalArgumentException(
                        "未注册的 workflow handler: " + handler + ". 已注册: " + registry.list());
                }
                return handlerOpt.get().execute(parseStepFromHandler(handler), ctx);
            }
        );
    }

    /**
     * 从 handler 字符串构造临时 WorkflowStep (仅用于 execute 调用, 不参与 DAG).
     */
    private static com.cloudinfra.common.core.workflow.WorkflowStep parseStepFromHandler(String handler) {
        return new com.cloudinfra.common.core.workflow.WorkflowStep(
            "ad-hoc", "plugin", java.util.List.of(), handler,
            java.util.Map.of(), null, null, null
        );
    }
}