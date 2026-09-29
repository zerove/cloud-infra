package com.cloudinfra.framework.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * gRPC Server 生命周期管理.
 *
 * <p>实现 {@link SmartLifecycle} 和 {@link InitializingBean}/{@link DisposableBean}
 * 确保 gRPC server 与 Spring 容器生命周期绑定:
 * <ul>
 *   <li>启动阶段: 扫描 {@link BindableService} bean, 构建并启动 Netty gRPC server</li>
 *   <li>运行阶段: 保持 server 运行, 处理 RPC 请求</li>
 *   <li>关闭阶段: 优雅关闭 server, 等待在途请求完成</li>
 * </ul>
 *
 * <p>设计要点:
 * <ul>
 *   <li>使用每任务虚拟线程执行器，Netty 单独管理网络线程</li>
 *   <li>支持 SSL/TLS 配置</li>
 *   <li>支持进程内测试模式 ({@code inProcessServerName})</li>
 *   <li>配置驱动: 所有参数来自 {@link CloudInfraGrpcProperties}</li>
 * </ul>
 *
 * @author Cloud-Infra Team
 * @since M7-02 Phase C (原生自动配置)
 */
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle, InitializingBean, DisposableBean {

    private final CloudInfraGrpcProperties properties;
    private final List<BindableService> bindableServices;
    private final List<ServerInterceptor> interceptors;

    private volatile Server server;
    private ExecutorService executor;
    private volatile boolean running = false;

    public List<BindableService> getBindableServices() {
        return bindableServices;
    }

    public Server getServer() {
        return server;
    }

    public GrpcServerLifecycle(CloudInfraGrpcProperties properties,
                                @Nullable List<BindableService> bindableServices) {
        this(properties, bindableServices, null);
    }

    @Autowired
    public GrpcServerLifecycle(CloudInfraGrpcProperties properties,
                                @Nullable List<BindableService> bindableServices,
                                @Nullable List<ServerInterceptor> interceptors) {
        this.properties = properties;
        this.bindableServices = bindableServices != null ? List.copyOf(bindableServices) : List.of();
        this.interceptors = interceptors != null ? List.copyOf(interceptors) : List.of();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!properties.isEnabled()) {
            log.info("gRPC server disabled via configuration (cloudinfra.grpc.enabled=false)");
            return;
        }

        if (bindableServices.isEmpty()) {
            log.warn("No BindableService beans found. gRPC server will start but expose no services.");
        } else {
            log.info("Found {} BindableService bean(s): {}",
                bindableServices.size(),
                bindableServices.stream().map(s -> s.bindService().getServiceDescriptor().getName()).toList());
        }

        // 初始化只检查服务，服务器由 SmartLifecycle.start() 按阶段启动。
    }

    private void buildAndStartServer() throws IOException {
        if (properties.getInProcessServerName() != null && !properties.getInProcessServerName().isEmpty()) {
            // 进程内模式 (用于集成测试)
            log.info("Starting in-process gRPC server: {}", properties.getInProcessServerName());
            InProcessServerBuilder builder =
                InProcessServerBuilder.forName(properties.getInProcessServerName())
                    .directExecutor(); // 进程内模式使用 direct executor

            for (ServerInterceptor interceptor : interceptors) {
                builder.intercept(interceptor);
            }

            for (BindableService service : bindableServices) {
                builder.addService(service);
            }

            this.server = builder.build().start();
        } else {
            // 网络模式 (生产环境)
            log.info("Starting gRPC server on port {}", properties.getPort());
            NettyServerBuilder builder = NettyServerBuilder.forPort(properties.getPort())
                .executor(executor)
                .maxInboundMessageSize(properties.getMaxInboundMessageSize())
                .keepAliveTime(properties.getKeepAliveTimeNanos(), TimeUnit.NANOSECONDS)
                .keepAliveTimeout(properties.getKeepAliveTimeoutNanos(), TimeUnit.NANOSECONDS)
                .permitKeepAliveWithoutCalls(properties.getPermitKeepAliveWithoutCalls())
                .maxConnectionIdle(properties.getMaxConnectionIdleNanos(), TimeUnit.NANOSECONDS)
                .maxConnectionAge(properties.getMaxConnectionAgeNanos(), TimeUnit.NANOSECONDS)
                .maxConnectionAgeGrace(properties.getMaxConnectionAgeGraceNanos(), TimeUnit.NANOSECONDS);

            for (ServerInterceptor interceptor : interceptors) {
                builder.intercept(interceptor);
            }

            // SSL 配置
            if (properties.getSsl().isEnabled()) {
                configureSsl(builder);
            }

            for (BindableService service : bindableServices) {
                builder.addService(service);
            }

            this.server = builder.build().start();
        }

        this.running = true;
        log.info("gRPC server started successfully. Listening on {}",
            properties.getInProcessServerName() != null
                ? "in-process:" + properties.getInProcessServerName()
                : "port " + properties.getPort());
    }

    private void configureSsl(NettyServerBuilder builder) {
        String certChainFile = properties.getSsl().getCertChainFile();
        String privateKeyFile = properties.getSsl().getPrivateKeyFile();

        if (certChainFile == null || certChainFile.isBlank() ||
            privateKeyFile == null || privateKeyFile.isBlank()) {
            throw new IllegalStateException(
                "SSL enabled but cert-chain-file or private-key-file not configured");
        }

        try (java.io.InputStream certStream = resolveResourceAsStream(certChainFile);
             java.io.InputStream keyStream = resolveResourceAsStream(privateKeyFile)) {

            if (certStream == null || keyStream == null) {
                throw new IllegalStateException(
                    "SSL certificate or key resource not found: " + certChainFile + ", " + privateKeyFile);
            }

            builder.useTransportSecurity(certStream, keyStream);
            log.info("gRPC SSL/TLS enabled with cert: {}, key: {}", certChainFile, privateKeyFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to configure gRPC SSL", e);
        }
    }

    private java.io.InputStream resolveResourceAsStream(String path) {
        if (path.startsWith("classpath:")) {
            String classpathPath = path.substring("classpath:".length());
            return this.getClass().getClassLoader().getResourceAsStream(classpathPath);
        } else if (path.startsWith("file:")) {
            try {
                return new java.io.FileInputStream(path.substring("file:".length()));
            } catch (java.io.FileNotFoundException e) {
                return null;
            }
        } else {
            try {
                return new java.io.FileInputStream(path);
            } catch (java.io.FileNotFoundException e) {
                return null;
            }
        }
    }

    @Override
    public void start() {
        if (!running && properties.isEnabled()) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                buildAndStartServer();
            } catch (IOException | RuntimeException e) {
                if (server != null) {
                    server.shutdownNow();
                }
                executor.shutdownNow();
                throw new IllegalStateException("Failed to start gRPC server", e);
            }
        }
    }

    @Override
    public void stop() {
        stop(null);
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Stopping gRPC server...");
        try {
            if (server != null) {
                // 优雅关闭: 等待在途请求完成
                server.shutdown();
                boolean terminated = server.awaitTermination(30, TimeUnit.SECONDS);
                if (!terminated) {
                    log.warn("gRPC server did not terminate gracefully within 30s, forcing shutdown");
                    server.shutdownNow();
                    server.awaitTermination(10, TimeUnit.SECONDS);
                }
                log.info("gRPC server stopped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping gRPC server", e);
            if (server != null) {
                server.shutdownNow();
            }
        } finally {
            running = false;
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (callback != null) {
                callback.run();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running && server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        // 低优先级: 让其他基础设施 (数据库、Redis、Nacos 等) 先启动
        return Integer.MAX_VALUE - 1000;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isEnabled();
    }

    @Override
    public void destroy() throws Exception {
        stop();
    }
}
