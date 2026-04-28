package com.cloudinfra.system;

import com.cloudinfra.system.config.MyBatisConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.mockito.Mockito.mock;

@Configuration
@ComponentScan(basePackages = {"com.cloudinfra.system"}, 
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = SystemApplication.class))
@EnableAutoConfiguration
@Import(MyBatisConfig.class)
public class SystemTestConfig {
    @Bean
    public RedissonClient redissonClient() {
        return mock(RedissonClient.class);
    }
}
