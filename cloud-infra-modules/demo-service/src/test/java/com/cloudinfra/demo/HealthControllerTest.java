package com.cloudinfra.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 服务启动冒烟测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HealthControllerTest {

    @Autowired(required = false)
    private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
