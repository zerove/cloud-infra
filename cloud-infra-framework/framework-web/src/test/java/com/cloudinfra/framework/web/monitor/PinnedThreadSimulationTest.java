package com.cloudinfra.framework.web.monitor;

import ch.qos.logback.classic.LoggerContext;
import com.cloudinfra.test.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

public class PinnedThreadSimulationTest extends BaseIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setupAppender() {
        // Ensure the meter registry is added to the global registry
        if (!io.micrometer.core.instrument.Metrics.globalRegistry.getRegistries().contains(meterRegistry)) {
            io.micrometer.core.instrument.Metrics.addRegistry(meterRegistry);
        }
        System.out.println("DEBUG: Global registry size: " + io.micrometer.core.instrument.Metrics.globalRegistry.getRegistries().size());

        LoggerContext loggerContext = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        PinnedThreadMetricAppender appender = new PinnedThreadMetricAppender();
        appender.setContext(loggerContext);
        appender.start();
        loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @SpringBootApplication
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    @Test
    void shouldCapturePinnedThreadEvent() throws InterruptedException {
        // 记录符合条件的日志，手动触发监控逻辑
        org.slf4j.LoggerFactory.getLogger(PinnedThreadSimulationTest.class).info("Pinned thread triggered in synchronized block");

        // 给一点缓冲时间让 Logback 处理日志
        Thread.sleep(500);

        // 验证指标是否存在且数值大于 0
        double count = io.micrometer.core.instrument.Metrics.globalRegistry.get("gateway.threads.pinned.count").counter().count();
        assertThat(count).isGreaterThan(0);
    }
}
