package com.cloudinfra.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;

@EnableDiscoveryClient
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableTransactionManagement
@SpringBootApplication
// 全量扫描 com.cloudinfra (含 framework/common 组件), 但排除 starter 包:
// starter 配置类走 AutoConfiguration.imports 延迟导入,
// 避免 @ConditionalOnMissingBean 在用户配置之前评估导致重复注册 Bean
@ComponentScan(
    basePackages = "com.cloudinfra",
    excludeFilters = {
        // 只排除 starter 自身的 component bean, 不排除其下的 auto-config 类
        // 之前 pattern "com\\.cloudinfra\\.starter\\..*" 把整个 starter 排了, 导致
        // FeignAuthInterceptor / InternalCallSecretProvider 都没注册
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.cloudinfra\\.starter\\.domain\\..*")
    }
)
@Import({RedissonConfig.class, MybatisPlusConfig.class, PasswordEncoderConfig.class})
public class SystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
