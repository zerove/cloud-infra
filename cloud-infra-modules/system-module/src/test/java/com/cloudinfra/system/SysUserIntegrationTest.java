package com.cloudinfra.system;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class SysUserIntegrationTest {

    @Autowired
    private ISysUserService userService;

    @Test
    public void testUserPageListWithDataScope() {
        Page<SysUser> page = new Page<>(1, 10);
        SysUser user = new SysUser();
        
        // 触发分页与 @DataScope 拦截
        var result = userService.selectUserList(page, user);
        
        assertThat(result).isNotNull();
    }
}
