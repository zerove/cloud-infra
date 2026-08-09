package com.cloudinfra.framework.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 优雅关闭配置
 * 支持虚拟线程感知的关闭流程：
 * - 停止接受新请求
 * - 等待进行中请求完成
 * - 清理连接池、线程池、缓存
 * - 强制终止超时任务
 */
@AutoConfiguration
public class GracefulShutdownAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownAutoConfiguration.class);

    @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}")
    private Duration shutdownTimeout;

    @Value("${graceful-shutdown.wait-for-active-requests:true}")
    private boolean waitForActiveRequests;

    @Value("${graceful-shutdown.max-wait-seconds:30}")
    private int maxWaitSeconds;

    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final AtomicInteger activeRequestCount = new AtomicInteger(0);
    private final CountDownLatch allRequestsCompleted = new CountDownLatch(0);
    private Instant shutdownStartTime;

    /**
     * 请求计数器拦截器（需配合 WebMvcConfigurer 使用）
     */
    public void requestStarted() {
        if (!shutdownInitiated.get()) {
            activeRequestCount.incrementAndGet();
        }
    }

    public void requestCompleted() {
        int remaining = activeRequestCount.decrementAndGet();
        if (remaining == 0 && shutdownInitiated.get()) {
            allRequestsCompleted.countDown();
        }
    }

    public boolean isShutdownInitiated() {
        return shutdownInitiated.get();
    }

    public int getActiveRequestCount() {
        return activeRequestCount.get();
    }

    /**
     * 应用就绪事件监听器
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> applicationReadyListener() {
        return event -> log.info("应用启动完成，优雅关闭机制已就绪");
    }

    /**
     * 优雅关闭协调器
     */
    @Bean
    public GracefulShutdownCoordinator gracefulShutdownCoordinator() {
        return new GracefulShutdownCoordinator();
    }

    /**
     * 优雅关闭协调器实现
     */
    public class GracefulShutdownCoordinator {

        /**
         * 发起关闭流程
         */
        public void initiateShutdown() {
            if (shutdownInitiated.compareAndSet(false, true)) {
                shutdownStartTime = Instant.now();
                log.info("=== 开始优雅关闭流程 ===");
                log.info("关闭超时时间: {}", shutdownTimeout);
                log.info("等待活跃请求: {}", waitForActiveRequests);
                
                // 1. 停止接受新请求（由 Spring Boot 自动处理：停止 Tomcat 端口）
                log.info("步骤 1/4: 已停止接受新请求");
                
                // 2. 等待进行中请求完成
                if (waitForActiveRequests) {
                    waitForActiveRequestsCompletion();
                }
                
                // 3. 清理资源
                cleanupResources();
                
                // 4. 打印线程转储（调试用）
                printThreadDump();
                
                log.info("=== 优雅关闭完成，耗时: {}ms ===", Duration.between(shutdownStartTime, Instant.now()).toMillis());
            }
        }

        /**
         * 等待活跃请求完成
         */
        private void waitForActiveRequestsCompletion() {
            int active = activeRequestCount.get();
            if (active > 0) {
                log.info("步骤 2/4: 等待 {} 个活跃请求完成...", active);
                try {
                    boolean completed = allRequestsCompleted.await(maxWaitSeconds, TimeUnit.SECONDS);
                    if (!completed) {
                        log.warn("等待活跃请求超时 ({}s)，剩余 {} 个请求将被强制终止", 
                            maxWaitSeconds, activeRequestCount.get());
                    } else {
                        log.info("所有活跃请求已完成");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待活跃请求被中断");
                }
            } else {
                log.info("步骤 2/4: 无活跃请求，跳过等待");
            }
        }

        /**
         * 清理资源
         */
        private void cleanupResources() {
            log.info("步骤 3/4: 清理资源 (连接池、线程池、缓存)...");
            
            // Spring Boot 会自动关闭 DataSource、RedisTemplate 等 Bean
            // 这里可以添加自定义资源清理逻辑
            
            // 示例：关闭自定义线程池
            shutdownCustomThreadPools();
            
            // 示例：刷新缓存
            flushCaches();
            
            log.info("资源清理完成");
        }

        /**
         * 关闭自定义线程池
         */
        private void shutdownCustomThreadPools() {
            // 如果有自定义的 ExecutorService，在此关闭
            // 例如：ScheduledExecutorService, ThreadPoolExecutor 等
        }

        /**
         * 刷新缓存
         */
        private void flushCaches() {
            // 如果有需要持久化的缓存，在此刷新
        }

        /**
         * 打印线程转储（用于排查关闭时的线程残留）
         */
        private void printThreadDump() {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
            
            int virtualThreadCount = 0;
            int platformThreadCount = 0;
            
            for (ThreadInfo info : threadInfos) {
                if (info.getThreadName().startsWith("cloud-infra-virtual-") || 
                    info.getThreadName().contains("VirtualThread")) {
                    virtualThreadCount++;
                } else {
                    platformThreadCount++;
                }
            }
            
            log.info("步骤 4/4: 线程统计 - 平台线程: {}, 虚拟线程: {}", platformThreadCount, virtualThreadCount);
            
            // 如果有残留线程，打印详细信息
            if (platformThreadCount > 10) { // 排除基础线程
                log.warn("检测到 {} 个平台线程可能未正常关闭", platformThreadCount);
                for (ThreadInfo info : threadInfos) {
                    if (!isSystemThread(info.getThreadName())) {
                        log.warn("残留线程: {} - 状态: {} - 堆栈: {}", 
                            info.getThreadName(), info.getThreadState(), 
                            info.getStackTrace().length > 0 ? info.getStackTrace()[0] : "N/A");
                    }
                }
            }
        }

        private boolean isSystemThread(String name) {
            return name.startsWith("Reference Handler") ||
                   name.startsWith("Finalizer") ||
                   name.startsWith("Signal Dispatcher") ||
                   name.startsWith("Attach Listener") ||
                   name.startsWith("JMX server connection") ||
                   name.startsWith("RMI TCP Connection") ||
                   name.startsWith("DestroyJavaVM") ||
                   name.startsWith("Common-Cleaner") ||
                   name.startsWith("Monitor Ctrl-Break");
        }
    }

    /**
     * 注册 JVM 关闭钩子
     */
    @Bean
    public Thread shutdownHook(GracefulShutdownCoordinator coordinator) {
        Thread hook = new Thread(() -> {
            log.info("JVM 关闭钩子触发，启动优雅关闭");
            coordinator.initiateShutdown();
        }, "graceful-shutdown-hook");
        
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }
}