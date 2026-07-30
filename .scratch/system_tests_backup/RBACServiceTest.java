package com.cloudinfra.system.service;

import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.service.impl.RBACServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RBACServiceTest {

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    @InjectMocks
    private RBACServiceImpl rbacService;

    @Test
    void testGetUserPermissions() {
        Long userId = 1L;
        List<String> expectedPerms = List.of("user:add", "user:delete");
        
        when(sysPermissionMapper.selectPermsByUserId(userId)).thenReturn(expectedPerms);
        
        List<String> perms = rbacService.getUserPermissions(userId);
        
        assertThat(perms).hasSize(2);
        assertThat(perms).contains("user:add", "user:delete");
    }
}
