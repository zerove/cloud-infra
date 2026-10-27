package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.EventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true")
public class VirtualThreadPinnedMonitor {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadPinnedMonitor.class);

    private final MeterRegistry meterRegistry;
    private final Timer pinnedDurationTimer;
    private final Timer pinnedBySourceTimer;
    private final Map<String, LongAdder> pinnedBySourceCounters = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread monitorThread;

    public VirtualThreadPinnedMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // 全局总时长
        this.pinnedDurationTimer = Timer.builder("jvm.threads.virtual.pinned.duration")
            .description("Duration of virtual thread pinning events")
            .register(meterRegistry);
        // 按线程源切分
        this.pinnedBySourceTimer = Timer.builder("jvm.threads.virtual.pinned.by.source")
            .description("Duration of virtual thread pinning events grouped by source thread")
            .register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        monitorThread = Thread.ofPlatform().name("vthread-pinned-monitor").start(() -> {
            try (var es = EventStream.openRepository()) {
                es.onEvent("jdk.VirtualThreadPinned", event -> {
                    long durationNanos = event.getDuration().toNanos();
                    String threadName = event.getThread("eventThread").getJavaName();
                    long source = classifySource(threadName);

                    pinnedDurationTimer.record(Duration.ofNanos(durationNanos));
                    pinnedBySourceTimer.record(Duration.ofNanos(durationNanos));

                    // 每线程源计数
                    LongAdder adder = pinnedBySourceCounters.computeIfAbsent(sourceKey(source), k -> new LongAdder());
                    adder.increment();

                    log.warn("Virtual Thread Pinned: duration={}ms, source={}, thread={}",
                        durationNanos / 1_000_000.0, sourceKey(source), threadName);
                });
                es.start();
            } catch (java.io.IOException | RuntimeException e) {
                if (running.get()) {
                    log.error("Failed to start JFR event stream for pinning detection", e);
                }
            }
        });
    }

    /**
     * 从 JFR 线程名提取"线程源标识": 取最后一个 '.' 后缀。
     * 例如 "cloud-system-worker.vthread-22" -> "22";
     * "cloud-infra-notification.notifyConsumer.vthread-5" -> "5"。
     * 用于按模块维度拆分健康指标，避免全局单一布尔值掩盖具体路径劣化。
     */
    private static long classifySource(String threadName) {
        int idx = threadName.lastIndexOf('.');
        return idx > 0 ? idx : -1L;
    }

    private static String sourceKey(long sourceNum) {
        return "src-" + sourceNum;
    }

    /**
     * 健康细粒度: 累计各线程源钉住次数。
     */
    public Map<String, LongAdder> getPinnedBySourceCounters() {
        return java.util.Collections.unmodifiableMap(pinnedBySourceCounters);
    }

    /**
     * 每秒一个 source key 最近 60 秒的实时钉住事件数（留作扩展点）。
     * 当前用全局累计值替代，避免引入时间窗口滚动基础设施复杂度。
     */
    public long countForSource(String sourceKey) {
        LongAdder adder = pinnedBySourceCounters.get(sourceKey);
        return adder == null ? 0L : adder.sum();
    }

    public boolean isRunning() {
        return monitorThread != null && monitorThread.isAlive();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }
}