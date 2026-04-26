package com.cloudinfra.system;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.test.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(classes = SystemTestConfig.class)
public class SysUserIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ISysUserService sysUserService;

    @Test
    void testCreateAndGet() {
        log.info("Starting SysUserIntegrationTest - this log should have a traceId");
        
        SysUser user = new SysUser();
        user.setUsername("test_admin");
        user.setPassword("123456");
        user.setEmail("test@example.com");
        
        boolean saved = sysUserService.save(user);
        assertThat(saved).isTrue();
        
        SysUser found = sysUserService.getByUsername("test_admin");
        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("test@example.com");
        
        log.info("Successfully verified SysUser in integration test with traceId: {}", found.getUsername());
    }
}
