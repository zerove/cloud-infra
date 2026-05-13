package com.cloudinfra.framework.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinfra.test.BaseIntegrationTest;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = TracePropagationTest.TestConfig.class
)
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
public class TracePropagationTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(
        TracePropagationTest.class
    );

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.ComponentScan
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

    @Component
    public static class AsyncService {

        private static final Logger log = LoggerFactory.getLogger(
            AsyncService.class
        );

        @Async
        public CompletableFuture<String> doAsyncWork() {
            String traceId = MDC.get("traceId");
            log.info("Async thread traceId: {}", traceId);
            return CompletableFuture.completedFuture(traceId);
        }
    }
}
