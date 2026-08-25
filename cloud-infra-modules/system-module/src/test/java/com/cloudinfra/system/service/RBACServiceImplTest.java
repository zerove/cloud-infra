package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

/**
 * RBAC 服务单元测试
 * 覆盖角色分配、权限分配、权限查询链路
 */
@ExtendWith(MockitoExtension.class)
public class RBACServiceImplTest {

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    @Mock
    private RedisService redisService;

    @InjectMocks
    private RBACServiceImpl rbacService;

    @BeforeEach
    void setUp() {
        lenient().when(sysUserRoleMapper.insert(any(SysUserRole.class))).thenReturn(1);
        lenient().when(sysRolePermissionMapper.insert(any(SysRolePermission.class))).thenReturn(1);
    }

    @Test
    void shouldAssignRolesAndPublishRefreshEvent() {
        rbacService.assignRolesToUser(1L, List.of(10L, 20L));

        // 先清空旧关系，再插入新关系
        verify(sysUserRoleMapper).delete(any(Wrapper.class));
        verify(sysUserRoleMapper, times(2)).insert(any(SysUserRole.class));
        // 发布权限刷新事件
        verify(redisService).publish("auth:refresh:channel", "user:1");
    }

    @Test
    void shouldAssignPermissionsAndPublishRefreshEvent() {
        // 角色授权变更前先查出受影响用户，变更后逐一发布 user: 刷新事件
        // （监听器只处理 user: 前缀；role: 消息无法在下游反查用户-角色映射）
        com.cloudinfra.system.domain.SysUserRole relation =
                new com.cloudinfra.system.domain.SysUserRole();
        relation.setUserId(1L);
        relation.setRoleId(2L);
        when(sysUserRoleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(relation));

        rbacService.assignPermissionsToRole(2L, List.of(100L));

        verify(sysRolePermissionMapper).delete(any(Wrapper.class));
        verify(sysRolePermissionMapper, times(1)).insert(any(SysRolePermission.class));
        verify(redisService).publish("auth:refresh:channel", "user:1");
    }

    @Test
    void shouldReturnUserPermissions() {
        when(sysPermissionMapper.selectPermsByUserId(1L)).thenReturn(List.of("system:user:list", "system:role:list"));

        List<String> permissions = rbacService.getUserPermissions(1L);

        assertThat(permissions).containsExactly("system:user:list", "system:role:list");
        verify(sysPermissionMapper).selectPermsByUserId(1L);
    }
}
