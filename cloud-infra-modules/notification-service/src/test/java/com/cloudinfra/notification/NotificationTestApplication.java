package com.cloudinfra.notification;

import com.cloudinfra.notification.config.NotificationSecurityConfig;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.concurrent.Executors;

/**
 * 通知服务集成测试专用应用。
 *
 * 与主应用的区别：
 * <ul>
 *   <li>排除 FrameworkSecurityAutoConfiguration：测试环境不注册 Feign 权限客户端，避免 loadbalancer 依赖</li>
 *   <li>排除主类 NotificationApplication 被组件扫描二次注册为配置类</li>
 *   <li>不使用懒加载，便于测试上下文快速启动</li>
 * </ul>
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableAsync
@EnableTransactionManagement
@SpringBootApplication(
    exclude = {
        com.cloudinfra.framework.security.config.FrameworkSecurityAutoConfiguration.class
    }
)
@ComponentScan(basePackages = {"com.cloudinfra.notification"},
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.cloudinfra\\.notification\\.NotificationApplication"),
            // 排除 IT 内部声明的嵌套装配类 (形如 XxxIT$MultiReplicaTestApplication)。
            // 它们是普通 @Configuration, 落在扫描范围内就会被当成生产 Bean 注册:
            // 其 dataSource 与 DataSourceAutoConfiguration 冲突 (BeanDefinitionOverrideException),
            // 其 stringRedisTemplate 在容器启动前取映射端口而抛 IllegalStateException,
            // 最终污染所有复用本测试应用的测试上下文。
            // file-service 侧已有同问题的 FileServiceContextExcludeFilter, 此处用同一思路。
            //
            // 2026-09-30 收紧: 原正则 com\.cloudinfra\.notification\..*\..*\$.* 要求中间还有
            // 一个包分隔符, 因此漏掉了包根下的 NotificationRecoveryMultiReplicaIT$..., 同时
            // 又会连带排除与 IT 无关的嵌套类。改为只匹配 "以 IT 结尾的测试类的嵌套类":
            //   * NotificationRecoveryMultiReplicaIT$MultiReplicaTestApplication  命中
            //   * WebSocketIdentityTrustIT$WsTestConfiguration                    命中
            //   * NotificationServiceIntegrationTest$TestSecurityConfig          不命中
            //     —— 但它带 @TestConfiguration, 本就由 Spring Boot 的 TypeExcludeFilter 排除,
            //        无需本正则兜底。
            @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.cloudinfra\\.notification\\..*IT\\$.*"),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = NotificationSecurityConfig.class)
        })
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class NotificationTestApplication {

    /**
     * 提供 @Qualifier("applicationTaskExecutor") 依赖：NotificationServiceImpl 等组件在构造期要求该 bean。
     * IT 不启用 spring.threads.virtual.enabled，VirtualThreadConfig 不会装配，故在测试应用中显式注册。
     */
    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
