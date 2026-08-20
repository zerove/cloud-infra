package com.cloudinfra.search;

import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import com.cloudinfra.search.config.SearchSecurityConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 搜索服务集成测试专用应用。
 *
 * 与主应用的区别：
 * <ul>
 *   <li>排除 FrameworkSecurityAutoConfiguration：测试环境不注册 Feign 权限客户端，避免 loadbalancer 依赖</li>
 *   <li>排除主类 SearchApplication 被组件扫描二次注册为配置类</li>
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
@ComponentScan(basePackages = {"com.cloudinfra.search"},
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.cloudinfra\\.search\\.SearchApplication"),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = SearchSecurityConfig.class)
        })
@Import({RedissonConfig.class, TracingAutoConfiguration.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class SearchTestApplication {
}