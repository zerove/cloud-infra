package com.cloudinfra.framework.grpc;

import com.cloudinfra.grpc.lib.EchoRequest;
import com.cloudinfra.grpc.lib.EchoServiceGrpc;
import com.cloudinfra.grpc.lib.EchoServiceImpl;
import com.cloudinfra.grpc.lib.HealthRequest;
import com.cloudinfra.grpc.lib.HealthResponse;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 直接调用容器托管的服务器，不创建替代服务器或回退服务。 */
class GrpcServerLifecycleIntegrationTest {

    @Test
    void initializationMustNotStartBeforeLifecyclePhase() throws Exception {
        var properties = new CloudInfraGrpcProperties();
        properties.setInProcessServerName(UUID.randomUUID().toString());
        var lifecycle = new GrpcServerLifecycle(properties, List.of(new EchoServiceImpl()));
        try {
            lifecycle.afterPropertiesSet();
            assertFalse(lifecycle.isRunning());
            assertNull(lifecycle.getServer());
        } finally {
            lifecycle.destroy();
        }
    }

    @Test
    void containerStartsRegistersAndStopsActualServer() throws Exception {
        String name = UUID.randomUUID().toString();
        GrpcServerLifecycle lifecycle;
        try (var context = context(Map.of("cloudinfra.grpc.in-process-server-name", name))) {
            lifecycle = context.getBean(GrpcServerLifecycle.class);
            assertTrue(lifecycle.isRunning());
            assertEquals(1, lifecycle.getBindableServices().size());
            ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                assertRpc(channel);
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        assertFalse(lifecycle.isRunning());
        assertTrue(lifecycle.getServer().isTerminated());
    }

    @Test
    void networkServerCanRestartWithFreshExecutor() throws Exception {
        try (var context = context(Map.of("cloudinfra.grpc.port", "0"))) {
            var lifecycle = context.getBean(GrpcServerLifecycle.class);
            for (int i = 0; i < 2; i++) {
                assertTrue(lifecycle.isRunning());
                ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", lifecycle.getServer().getPort())
                    .usePlaintext().build();
                try {
                    assertRpc(channel);
                } finally {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
                lifecycle.stop();
                assertTrue(lifecycle.getServer().isTerminated());
                lifecycle.start();
            }
        }
    }

    @Test
    void disabledConfigurationDoesNotRegisterLifecycle() {
        try (var context = context(Map.of("cloudinfra.grpc.enabled", "false"))) {
            assertTrue(context.getBeansOfType(GrpcServerLifecycle.class).isEmpty());
        }
    }

    @Test
    void emptyServiceCollectionStillAllowsLifecycle() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "cloudinfra.grpc.in-process-server-name", UUID.randomUUID().toString())));
            context.register(GrpcServerAutoConfiguration.class);
            context.refresh();
            assertTrue(context.getBean(GrpcServerLifecycle.class).isRunning());
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(EchoServiceImpl.class);
        context.register(GrpcServerAutoConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private static void assertRpc(ManagedChannel channel) {
        var stub = EchoServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS);
        assertEquals("hellohellohello", stub.echo(EchoRequest.newBuilder().setMessage("hello").setCount(3).build()).getMessage());
        assertEquals("world", stub.echo(EchoRequest.newBuilder().setMessage("world").build()).getMessage());
        assertEquals(HealthResponse.ServingStatus.SERVING, stub.health(HealthRequest.getDefaultInstance()).getStatus());
    }
}
