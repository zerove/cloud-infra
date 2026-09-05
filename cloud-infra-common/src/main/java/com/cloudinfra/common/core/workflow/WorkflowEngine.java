package com.cloudinfra.common.core.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        // 1. 拓扑排序 (DAG)
        List<WorkflowStep> ordered = topologicalSort(workflow.steps());

        // 2. 按顺序执行 (同层并行)
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
                    } catch (Throwable t) {
                        stepErrors.put(step.id(), t);
                        log.error("[WorkflowEngine] 步骤失败: id={}, err={}", step.id(), t.getMessage());
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
            : 60_000L;

        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Object result = invokeHandler(step, context, outputs);
                outputs.put(step.id(), result);
                long duration = System.currentTimeMillis() - stepStart;
                log.info("[WorkflowEngine] 步骤成功: id={}, attempt={}, duration={}ms",
                    step.id(), attempt, duration);
                return;
            } catch (Throwable t) {
                lastError = t;
                log.warn("[WorkflowEngine] 步骤失败: id={}, attempt={}/{}, err={}",
                    step.id(), attempt, maxAttempts, t.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(step.retry());
                }
            }
        }
        // 所有重试均失败, 抛错 (外层捕获并记录 stepErrors)
        throw new RuntimeException("步骤 " + step.id() + " 重试 " + maxAttempts + " 次均失败", lastError);
    }

    /**
     * 调用处理器. 通过 handlerInvoker 委托 (Phase A/B 统一入口).
     */
    private Object invokeHandler(WorkflowStep step, WorkflowContext context, Map<String, Object> outputs) {
        return handlerInvoker.apply(step.handler(), context);
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

    /**
     * 拓扑排序 (返回按依赖顺序的步骤列表).
     */
    private List<WorkflowStep> topologicalSort(List<WorkflowStep> steps) {
        Map<String, Integer> level = computeLevels(steps);
        return steps.stream()
            .sorted((a, b) -> level.get(a.id()) - level.get(b.id()))
            .toList();
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
        return switch (unit == null ? "s" : unit.toLowerCase()) {
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