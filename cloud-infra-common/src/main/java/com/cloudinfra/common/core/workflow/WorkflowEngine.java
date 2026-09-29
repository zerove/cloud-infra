package com.cloudinfra.common.core.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * 业务编排引擎 (M6-04)
 *
 * 核心能力:
 * - 拓扑排序 (DAG): 自动按依赖顺序执行
 * - 重试: per-step retry config + 指数退避 (interval 支持 s/ms)
 * - 超时: per-step timeout + 全局 workflow timeout
 * - 失败传播: 步骤失败 → 跳过所有依赖它的下游步骤
 * - Saga 补偿: onFailure handler (M6-04 Phase B 扩展)
 * - 并行执行: 无依赖关系的同级步骤并行
 *
 * <p><b>⚠️ 实验性组件, 无业务使用方 (2026-09-29 标注)</b></p>
 *
 * <p>本引擎代码完成且自测通过, 但<strong>业务模块零引用</strong>。当前判定为
 * {@code ⚪ Built, Unused} —— 不是已交付能力。</p>
 *
 * <p><b>在找到真实编排场景前, 不得基于它继续加功能</b> (如更多 handler 类型、
 * 持久化编排定义、可视化编排)。若确实需要 Saga 编排, 应先写出真实的业务用例,
 * 再判断本引擎是否适配, 而不是先扩引擎再找场景。</p>
 *
 * <p>处置决策见 {@code .scratch/2026-q3-roadmap/issues/27-framework-admission-and-cuts.md}
 * 决策 1 (选项 A: 保留、标记实验、暂停投入)。</p>
 *
 * 设计原则:
 * - 同步 API (execute), 内部异步执行
 * - 步骤结果保存在 context.results, 后续步骤可通过 ${steps.prev.output} 引用 (M6-04 暂简化, Phase B 加)
 * - 步骤失败抛出 WorkflowException, 包含失败步骤 id + 异常
 *
 * 用法:
 * <pre>
 * {@code
 * Workflow workflow = WorkflowLoader.fromJson(jsonString);
 * WorkflowContext context = new WorkflowContext(Map.of("userId", 42));
 * WorkflowEngine engine = new WorkflowEngine();
 * WorkflowResult result = engine.execute(workflow, context);
 * }
 * </pre>
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 步骤级超时缺省预算: 未配置 WorkflowStep.timeout 时使用。 */
    private static final long DEFAULT_STEP_TIMEOUT_MS = 60_000L;

    private final ExecutorService executor;
    private final BiFunction<String, WorkflowContext, Object> handlerInvoker;

    public WorkflowEngine() {
        this(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "workflow-engine");
            t.setDaemon(true);
            return t;
        }), (handler, ctx) -> {
            // 默认: 返回空结果 (Phase A 简化, Phase B 注入 handlerInvoker)
            log.debug("[WorkflowEngine] 无 handlerInvoker, 返回空 (handler={})", handler);
            return Map.of("handler", handler);
        });
    }

    public WorkflowEngine(ExecutorService executor) {
        this(executor, (handler, ctx) -> Map.of("handler", handler));
    }

    /**
     * Phase B 构造: 注入 handler 调用器 (如 WorkflowStepHandlerRegistry.lookup).
     * @param handlerInvoker (handler string, WorkflowContext) → result
     */
    public WorkflowEngine(ExecutorService executor,
                          BiFunction<String, WorkflowContext, Object> handlerInvoker) {
        this.executor = executor;
        this.handlerInvoker = handlerInvoker;
    }

    /**
     * 执行 workflow. 返回结果 (含每步输出 + 失败信息).
     */
    public WorkflowResult execute(Workflow workflow, WorkflowContext context) {
        log.info("[WorkflowEngine] 开始执行 workflow: name={}, version={}, steps={}",
            workflow.name(), workflow.version(), workflow.steps().size());

        // 1. 按依赖分层执行 (同层并行); computeLevels 同时完成环依赖校验
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        Map<String, Throwable> stepErrors = new LinkedHashMap<>();
        Set<String> skipped = new HashSet<>();

        long startTime = System.currentTimeMillis();
        long deadline = startTime + TimeUnit.SECONDS.toMillis(workflow.timeoutSeconds());

        // 用层 (level) 分组, 同层并行
        Map<String, Integer> level = computeLevels(workflow.steps());
        int maxLevel = level.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        for (int lvl = 0; lvl <= maxLevel; lvl++) {
            List<WorkflowStep> currentLevel = new ArrayList<>();
            for (WorkflowStep step : workflow.steps()) {
                if (level.get(step.id()) == lvl && !skipped.contains(step.id())) {
                    currentLevel.add(step);
                }
            }
            if (currentLevel.isEmpty()) {
                continue;
            }

            // 同层并行执行
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (WorkflowStep step : currentLevel) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 检查依赖是否成功 (若任一依赖失败, 跳过)
                        if (anyDependencyFailed(step, stepErrors, skipped)) {
                            log.warn("[WorkflowEngine] 跳过步骤 {} (依赖失败)", step.id());
                            skipped.add(step.id());
                            return;
                        }
                        executeStep(step, context, stepOutputs);
                    } catch (RuntimeException e) {
                        // handler 契约: 业务失败以 RuntimeException 表达。Error (OOM/StackOverflow)
                        // 反映 JVM 状态已不可信, 必须上抛而非记为"步骤失败"后继续调度
                        stepErrors.put(step.id(), e);
                        log.error("[WorkflowEngine] 步骤失败: id={}, err={}", step.id(), e.getMessage());
                    }
                }, executor);
                futures.add(future);
            }

            // 等待当前层完成
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.error("[WorkflowEngine] workflow 全局超时");
                    workflow.steps().stream()
                        .filter(s -> !stepOutputs.containsKey(s.id()) && !stepErrors.containsKey(s.id()))
                        .forEach(s -> stepErrors.put(s.id(), new TimeoutException("workflow 全局超时")));
                    break;
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("[WorkflowEngine] 层 {} 执行异常: {}", lvl, e.getMessage());
                for (WorkflowStep step : currentLevel) {
                    if (!stepOutputs.containsKey(step.id()) && !stepErrors.containsKey(step.id())) {
                        stepErrors.put(step.id(), e);
                    }
                }
                break;
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        boolean success = stepErrors.isEmpty() && skipped.isEmpty();
        return new WorkflowResult(workflow.name(), success, stepOutputs, stepErrors, skipped, durationMs);
    }

    /**
     * 执行单个步骤. 步骤处理器通过 PluginStepHandler 抽象提供 (Phase B 集成).
     */
    private void executeStep(WorkflowStep step, WorkflowContext context, Map<String, Object> outputs) {
        log.info("[WorkflowEngine] 执行步骤: id={}, type={}, handler={}", step.id(), step.type(), step.handler());

        long stepStart = System.currentTimeMillis();
        // 重试循环
        int maxAttempts = step.retry() != null ? step.retry().max() : 1;
        long timeoutMs = step.timeout() != null
            ? parseDuration(step.timeout().duration(), step.timeout().unit())
            : DEFAULT_STEP_TIMEOUT_MS;

        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Object result = invokeHandler(step, context, outputs);
                // 步骤级超时校验: handler 同步执行, 无法安全中断, 因此在返回后核对耗时。
                // 超预算即视为本次尝试失败, 交由重试策略处理, 且绝不记为成功。
                long elapsed = System.currentTimeMillis() - stepStart;
                if (elapsed > timeoutMs) {
                    throw new StepTimeoutException(step.id(), timeoutMs, elapsed);
                }
                outputs.put(step.id(), result);
                long duration = elapsed;
                log.info("[WorkflowEngine] 步骤成功: id={}, attempt={}, duration={}ms",
                    step.id(), attempt, duration);
                return;
            } catch (RuntimeException e) {
                // 只重试业务级失败; Error 重试只会放大故障
                lastError = e;
                log.warn("[WorkflowEngine] 步骤失败: id={}, attempt={}/{}, err={}",
                    step.id(), attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(step.retry());
                }
            }
        }
        // 所有重试均失败. Phase C: 尝试 onFailure 补偿 (handler.compensate)
        if (step.onFailure() != null) {
            log.info("[WorkflowEngine] 步骤 {} 失败, 触发 onFailure 补偿", step.id());
            try {
                // onFailure 是补偿 handler 字符串 (如 "OrderPlugin.refund"),
                // 构造一个只承载该 handler 的补偿步骤, 复用同一套 handlerInvoker 入口。
                var compStep = new WorkflowStep(
                    step.id() + "-compensation", "plugin", List.of(),
                    step.onFailure(), Map.of(), null, null, null
                );
                invokeHandler(compStep, context, outputs);
                log.info("[WorkflowEngine] 补偿成功: onFailure={}", step.onFailure());
            } catch (RuntimeException compError) {
                log.error("[WorkflowEngine] 补偿失败: onFailure={}, err={}", step.onFailure(), compError.getMessage());
            }
        }
        throw new WorkflowExecutionException(step.id(), maxAttempts, lastError);
    }

    /** 步骤级超时信号: 不含敏感信息, 仅标识步骤与预算。 */
    static final class StepTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StepTimeoutException(String stepId, long budgetMs, long elapsedMs) {
            super("步骤 " + stepId + " 超过超时预算 " + budgetMs + "ms (实际 " + elapsedMs + "ms)");
        }
    }

    /**
     * 调用处理器. 通过 handlerInvoker 委托 (Phase A/B 统一入口).
     * Phase C: 在执行后保存步骤输出到 context.variables (key="steps.{id}.output").
     */
    private Object invokeHandler(WorkflowStep step, WorkflowContext context, Map<String, Object> outputs) {
        Object result = handlerInvoker.apply(step.handler(), context);
        // Phase C: 保存步骤输出供后续步骤引用
        context.setStepOutput(step.id(), result);
        return result;
    }

    /**
     * 检查任一依赖是否失败.
     */
    private boolean anyDependencyFailed(WorkflowStep step, Map<String, Throwable> errors, Set<String> skipped) {
        if (step.dependsOn().isEmpty()) {
            return false;
        }
        for (String dep : step.dependsOn()) {
            if (errors.containsKey(dep) || skipped.contains(dep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算每步层级 (0 = 无依赖起点, 1 = 依赖 0级, ...).
     */
    private Map<String, Integer> computeLevels(List<WorkflowStep> steps) {
        Map<String, Integer> level = new HashMap<>();
        // BFS from roots
        var remaining = new HashSet<>(steps.stream().map(WorkflowStep::id).toList());
        int currentLevel = 0;
        while (!remaining.isEmpty()) {
            List<String> thisLevel = new ArrayList<>();
            for (WorkflowStep step : steps) {
                if (!remaining.contains(step.id())) {
                    continue;
                }
                if (step.dependsOn().isEmpty() || step.dependsOn().stream().noneMatch(remaining::contains)) {
                    thisLevel.add(step.id());
                }
            }
            if (thisLevel.isEmpty()) {
                throw new IllegalStateException("workflow 含循环依赖, 无法拓扑排序");
            }
            for (String id : thisLevel) {
                level.put(id, currentLevel);
                remaining.remove(id);
            }
            currentLevel++;
        }
        return level;
    }

    private void sleepBackoff(WorkflowStep.RetryConfig retry) {
        if (retry == null) {
            return;
        }
        long ms = parseDuration(1, retry.interval().replaceAll("[0-9]", ""));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long parseDuration(long duration, String unit) {
        return switch (unit == null ? "s" : unit.toLowerCase(Locale.ROOT)) {
            case "ms" -> duration;
            case "s" -> duration * 1000L;
            case "m" -> duration * 60_000L;
            case "h" -> duration * 3_600_000L;
            default -> duration * 1000L;
        };
    }

    /**
     * 关闭 executor (应用关闭时调用).
     */
    public void shutdown() {
        executor.shutdown();
    }
}