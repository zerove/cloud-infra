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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true")
public class VirtualThreadPinnedMonitor {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadPinnedMonitor.class);
    
    private final MeterRegistry meterRegistry;
    private final Timer pinnedTimer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread monitorThread;

    public VirtualThreadPinnedMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.pinnedTimer = Timer.builder("jvm.threads.virtual.pinned")
            .description("Duration of virtual thread pinning events")
            .register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        monitorThread = Thread.ofPlatform().name("vthread-pinned-monitor").start(() -> {
            try (var es = EventStream.openRepository()) {
                es.onEvent("jdk.VirtualThreadPinned", event -> {
                    long durationNanos = event.getDuration().toNanos();
                    pinnedTimer.record(Duration.ofNanos(durationNanos));
                    
                    log.warn("Virtual Thread Pinned: duration={}ms, thread={}", 
                        durationNanos / 1_000_000.0, event.getThread("eventThread").getJavaName());
                });
                es.start();
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Failed to start JFR event stream for pinning detection", e);
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }

    public boolean isRunning() {
        return monitorThread != null && monitorThread.isAlive();
    }
}
