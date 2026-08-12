package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudinfra.common.domain.SysPermission;
import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限服务单元测试
 * 覆盖：角色权限缓存优先、批量缓存缺失回填、用户权限两级关系组装、缓存清理
 */
@ExtendWith(MockitoExtension.class)
public class SysPermissionServiceImplTest {

    @Mock
    private SysPermissionMapper permissionMapper;

    @Mock
    private SysRolePermissionMapper rolePermissionMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RMapCache<Long, List<SysPermission>> roleCache;

    @Mock
    private RMapCache<Long, List<String>> userCache;

    @InjectMocks
    private SysPermissionServiceImpl permissionService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(permissionService, "baseMapper", permissionMapper);
        // LambdaQueryWrapper 需要实体表信息缓存（MyBatis-Plus 单测环境不自动注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysRolePermission.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysPermissionEntity.class);
        lenient().when(redissonClient.getMapCache("sys:permissions:role:")).thenReturn((RMapCache) roleCache);
        lenient().when(redissonClient.getMapCache("sys:permissions:user:")).thenReturn((RMapCache) userCache);
    }

    private SysPermissionEntity entity(Long id, String perms) {
        SysPermissionEntity e = new SysPermissionEntity();
        e.setId(id);
        e.setPerms(perms);
        return e;
    }

    @Test
    void shouldReturnEmptyMapForEmptyRoleIds() {
        Map<Long, List<SysPermission>> result = permissionService.getPermissionsByRoleIds(List.of());

        assertThat(result).isEmpty();
        verify(rolePermissionMapper, never()).selectList(any());
    }

    @Test
    void shouldLoadRolePermissionsFromDbAndCache() {
        SysRolePermission rel = new SysRolePermission();
        rel.setRoleId(1L);
        rel.setPermissionId(100L);
        when(rolePermissionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rel));
        when(permissionMapper.selectBatchIds(List.of(100L))).thenReturn(List.of(entity(100L, "system:user:list")));

        Map<Long, List<SysPermission>> result = permissionService.getPermissionsByRoleIds(List.of(1L));

        assertThat(result.get(1L)).hasSize(1);
        assertThat(result.get(1L).get(0).getPerms()).isEqualTo("system:user:list");
        verify(roleCache).put(eq(1L), any(), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldLoadUserPermissionsFromDbAndCache() {
        when(userCache.get(1L)).thenReturn(null);
        when(permissionMapper.selectPermsByUserId(1L)).thenReturn(List.of("system:user:list"));

        List<String> result = permissionService.getPermissionsByUserId(1L);

        assertThat(result).containsExactly("system:user:list");
        verify(userCache).put(eq(1L), any(), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldReadUserPermissionsFromCacheFirst() {
        when(userCache.get(1L)).thenReturn(List.of("cached:perm"));

        List<String> result = permissionService.getPermissionsByUserId(1L);

        assertThat(result).containsExactly("cached:perm");
        verify(permissionMapper, never()).selectPermsByUserId(anyLong());
    }

    @Test
    void shouldAssembleUserPermissionsViaRoleChain() {
        // 用户 1 -> 角色 10
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(1L);
        userRole.setRoleId(10L);
        when(userCache.get(1L)).thenReturn(null);
        when(userRoleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(userRole));

        // 角色 10 -> 权限 100
        SysRolePermission rolePerm = new SysRolePermission();
        rolePerm.setRoleId(10L);
        rolePerm.setPermissionId(100L);
        when(rolePermissionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rolePerm));

        // 权限详情
        when(permissionMapper.selectBatchIds(List.of(100L))).thenReturn(List.of(entity(100L, "system:role:list")));

        Map<Long, List<String>> result = permissionService.getPermissionsByUserIds(List.of(1L));

        assertThat(result.get(1L)).containsExactly("system:role:list");
        verify(userCache).put(eq(1L), any(), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldClearUserPermissionCache() {
        permissionService.clearPermissionCache(5L);

        verify(userCache).remove(5L);
    }
}