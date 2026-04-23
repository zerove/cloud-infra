package com.cloudinfra.framework.web.monitor;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;

/**
 * 监控 Pinned Thread 事件的 Logback Appender
 */
public class PinnedThreadMetricAppender extends AppenderBase<ILoggingEvent> {

    private final Counter pinnedThreadCounter = Metrics.counter("gateway.threads.pinned.count");

    public PinnedThreadMetricAppender() {
        System.out.println("DEBUG: PinnedThreadMetricAppender initialized.");
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        String message = eventObject.getFormattedMessage();
        if (message != null && message.contains("Pinned thread")) {
            pinnedThreadCounter.increment();
        }
    }
}
