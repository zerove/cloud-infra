package com.cloudinfra.framework.tracing;

import com.cloudinfra.test.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnableAsync
public class TracePropagationTest extends BaseIntegrationTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class TestConfig {}

    @Autowired
    private AsyncService asyncService;

    @Test
    void testTraceIdPropagation() throws Exception {
        String testTraceId = "test-trace-12345";
        MDC.put("traceId", testTraceId);
        log.info("Main thread traceId: {}", MDC.get("traceId"));

        CompletableFuture<String> future = asyncService.doAsyncWork();
        String asyncTraceId = future.get();

        assertThat(asyncTraceId).isEqualTo(testTraceId);
        MDC.clear();
    }

    @Service
    public static class AsyncService {
        @Async
        public CompletableFuture<String> doAsyncWork() {
            String traceId = MDC.get("traceId");
            log.info("Async thread traceId: {}", traceId);
            return CompletableFuture.completedFuture(traceId);
        }
    }
}
