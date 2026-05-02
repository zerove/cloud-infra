package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.EventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true")
public class VirtualThreadPinnedMonitor {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadPinnedMonitor.class);
    private final Counter pinnedCounter = Metrics.counter("gateway.threads.pinned.count");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread monitorThread;

    @PostConstruct
    public void start() {
        monitorThread = Thread.ofPlatform().name("vthread-pinned-monitor").start(() -> {
            try (var es = EventStream.openRepository()) {
                es.onEvent("jdk.VirtualThreadPinned", event -> {
                    pinnedCounter.increment();
                    log.warn("Virtual Thread Pinned: duration={}ns, thread={}", 
                        event.getDuration().toNanos(), event.getThread("eventThread").getJavaName());
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
}
