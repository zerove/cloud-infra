package com.cloudinfra.grpc.lib;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EchoServiceImpl 单元测试.
 *
 * <p>不依赖 gRPC server 启动, 直接测试业务逻辑.
 */
@DisplayName("EchoService 单元测试")
class EchoServiceImplTest {

    private EchoServiceImpl echoService;

    @BeforeEach
    void setUp() {
        echoService = new EchoServiceImpl();
    }

    @Test
    @DisplayName("Echo RPC 应返回重复消息")
    void echo_shouldReturnRepeatedMessage() {
        // given
        EchoRequest request = EchoRequest.newBuilder()
            .setMessage("hello")
            .setCount(3)
            .build();
        AtomicReference<EchoResponse> captured = new AtomicReference<>();
        StreamObserver<EchoResponse> observer = capturingObserver(captured);

        // when
        echoService.echo(request, observer);

        // then
        EchoResponse response = captured.get();
        assertNotNull(response);
        assertEquals("hellohellohello", response.getMessage());
        assertTrue(response.getTimestamp() > 0);
        assertFalse(response.getServerVersion().isEmpty());
    }

    @Test
    @DisplayName("Echo RPC count=0 应返回原始消息")
    void echo_countZero_shouldReturnOriginalMessage() {
        // given
        EchoRequest request = EchoRequest.newBuilder()
            .setMessage("world")
            .setCount(0)
            .build();
        AtomicReference<EchoResponse> captured = new AtomicReference<>();
        StreamObserver<EchoResponse> observer = capturingObserver(captured);

        // when
        echoService.echo(request, observer);

        // then
        assertEquals("world", captured.get().getMessage());
    }

    @Test
    @DisplayName("Echo RPC count 超过上限应被截断")
    void echo_countTooLarge_shouldBeTruncated() {
        // given
        EchoRequest request = EchoRequest.newBuilder()
            .setMessage("x")
            .setCount(100)  // 远超上限 10
            .build();
        AtomicReference<EchoResponse> captured = new AtomicReference<>();
        StreamObserver<EchoResponse> observer = capturingObserver(captured);

        // when
        echoService.echo(request, observer);

        // then
        assertEquals(10, captured.get().getMessage().length());
    }

    @Test
    @DisplayName("Health RPC 应返回 SERVING 状态")
    void health_shouldReturnServingStatus() {
        // given
        AtomicReference<HealthResponse> captured = new AtomicReference<>();
        StreamObserver<HealthResponse> observer = capturingObserver(captured);

        // when
        echoService.health(HealthRequest.newBuilder().build(), observer);

        // then
        HealthResponse response = captured.get();
        assertNotNull(response);
        assertEquals(HealthResponse.ServingStatus.SERVING, response.getStatus());
        assertFalse(response.getVersion().isEmpty());
    }

    @Test
    @DisplayName("EchoWithMetadata 应复用 Echo 逻辑")
    void echoWithMetadata_shouldReuseEchoLogic() {
        // given
        EchoRequest request = EchoRequest.newBuilder()
            .setMessage("meta")
            .setCount(2)
            .build();
        AtomicReference<EchoResponse> captured = new AtomicReference<>();
        StreamObserver<EchoResponse> observer = capturingObserver(captured);

        // when
        echoService.echoWithMetadata(request, observer);

        // then
        assertEquals("metameta", captured.get().getMessage());
    }

    @SuppressWarnings("unchecked")
    private <T> StreamObserver<T> capturingObserver(AtomicReference<T> ref) {
        return new StreamObserver<T>() {
            @Override
            public void onNext(T value) {
                ref.set(value);
            }
            @Override
            public void onError(Throwable t) {
                fail("Unexpected error: " + t.getMessage());
            }
            @Override
            public void onCompleted() {
                // 完成回调
            }
        };
    }
}
