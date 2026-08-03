package com.cloudinfra.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.yml")
@EnableAutoConfiguration(exclude = {com.cloudinfra.framework.redis.config.RedissonConfig.class})
public class AuthIntegrationTest {

    @Test
    void contextLoads() {
    }
}
