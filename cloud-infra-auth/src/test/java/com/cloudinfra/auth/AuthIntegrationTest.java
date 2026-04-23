package com.cloudinfra.auth;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class AuthIntegrationTest extends BaseIntegrationTest {
    @Test
    void contextLoads() {
        assertThat(mariadb.isRunning()).isTrue();
        assertThat(redis.isRunning()).isTrue();
    }
}
