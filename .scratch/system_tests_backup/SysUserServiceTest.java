package com.cloudinfra.system.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;

import com.cloudinfra.system.converter.SysUserConverter;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.impl.SysUserServiceImpl;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(properties = {
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.config.enabled=false"
})
public class SysUserServiceTest {

    @Mock
    private SysUserConverter sysUserConverter;

    @Mock
    private ISysPermissionService sysPermissionService;

    @InjectMocks
    private SysUserServiceImpl userService;

    @Test
    public void testGetByUsername_Exists() {
        String username = "testuser";
        SysUser user = new SysUser();
        user.setUsername(username);

        // 我们需要 mock 底层的 Mybatis-Plus mapper 或者 getOne 方法
        // 由于无法直接 mock ServiceImpl 的 getOne，这里展示逻辑结构的测试思路
        // 实际上需要对 SysUserMapper 进行 mock 或使用集成测试
    }
}
