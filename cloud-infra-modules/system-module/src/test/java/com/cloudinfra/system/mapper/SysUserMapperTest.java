package com.cloudinfra.system.mapper;

import com.cloudinfra.common.test.BaseDatabaseTest;
import com.cloudinfra.system.domain.SysUser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@Sql("/schema.sql")
@Disabled("环境稳定性问题，待 CI/CD 环境准备就绪后再开启")
public class SysUserMapperTest extends BaseDatabaseTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @SpringBootApplication(scanBasePackages = "com.cloudinfra.system")
    static class TestConfig {
    }

    @Test
    void shouldInsertAndSelectUser() {
        SysUser user = new SysUser();
        user.setUsername("testuser");
        user.setPassword("password");
        
        sysUserMapper.insert(user);
        
        SysUser savedUser = sysUserMapper.selectById(user.getId());
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getUsername()).isEqualTo("testuser");
    }
}
