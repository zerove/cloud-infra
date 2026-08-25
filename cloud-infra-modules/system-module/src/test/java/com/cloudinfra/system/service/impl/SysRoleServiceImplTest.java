package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.converter.SysRoleConverter;
import com.cloudinfra.system.domain.SysRoleDept;
import com.cloudinfra.system.domain.SysRoleMenu;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.service.ISysRoleDeptService;
import com.cloudinfra.system.service.ISysRoleMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色服务单元测试
 * 覆盖：列表查询（过滤）、分页、角色创建（唯一性+菜单/数据权限关联）、更新、删除
 */
@ExtendWith(MockitoExtension.class)
public class SysRoleServiceImplTest {

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private ISysRoleMenuService roleMenuService;

    @Mock
    private ISysRoleDeptService roleDeptService;

    @Mock
    private SysRoleConverter roleConverter;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private RedisService redisService;

    @InjectMocks
    private SysRoleServiceImpl roleService;

    @BeforeEach
    void setUp() {
        // ServiceImpl 的 baseMapper 是继承 protected 字段，Mockito @InjectMocks 无法注入，反射设置
        ReflectionTestUtils.setField(roleService, "baseMapper", roleMapper);
    }

    @Test
    void shouldListRolesWithFilters() {
        SysRole role = new SysRole();
        role.setRoleName("管理");
        role.setRoleKey("admin");
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(role));

        List<SysRole> result = roleService.selectRoleList(role);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldPageRoles() {
        SysRole role = new SysRole();
        IPage<SysRole> page = new Page<>(1, 10);
        when(roleMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

        IPage<SysRole> result = roleService.selectRoleList(new Page<>(1, 10), role);

        assertThat(result).isSameAs(page);
    }

    @Test
    void shouldRejectDuplicateRoleKeyOnCreate() {
        SysRoleCreateDTO dto = new SysRoleCreateDTO();
        dto.setRoleKey("admin");
        when(roleMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(new SysRole());

        assertThatThrownBy(() -> roleService.insertRole(dto))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("已存在");

        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void shouldCreateRoleWithRelations() {
        SysRoleCreateDTO dto = new SysRoleCreateDTO();
        dto.setRoleName("运营");
        dto.setRoleKey("operator");
        dto.setRoleOrder(2);
        dto.setStatus(0);
        dto.setMenuIds(List.of(101L, 102L));
        dto.setDeptIds(List.of(201L));

        SysRole entity = new SysRole();
        entity.setId(10L);
        entity.setRoleKey("operator");
        entity.setMenuIds(dto.getMenuIds());
        entity.setDeptIds(dto.getDeptIds());
        when(roleConverter.createDtoToEntity(dto)).thenReturn(entity);
        when(roleMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(roleMapper.insert(any(SysRole.class))).thenReturn(1);
        when(roleMenuService.saveBatch(any())).thenReturn(true);
        when(roleDeptService.saveBatch(any())).thenReturn(true);

        int result = roleService.insertRole(dto);

        assertThat(result).isEqualTo(1);
        // 菜单 + 数据权限各存 2 条和 1 条
        org.mockito.ArgumentCaptor<List<SysRoleMenu>> menuCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(roleMenuService).saveBatch(menuCaptor.capture());
        assertThat(menuCaptor.getValue()).hasSize(2);
        org.mockito.ArgumentCaptor<List<SysRoleDept>> deptCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(roleDeptService).saveBatch(deptCaptor.capture());
        assertThat(deptCaptor.getValue()).hasSize(1);
    }

    @Test
    void shouldUpdateRoleRebuildRelations() {
        SysRoleUpdateDTO dto = new SysRoleUpdateDTO();
        dto.setRoleId(10L);
        dto.setRoleName("新名字");
        dto.setMenuIds(List.of(1L));
        dto.setDeptIds(List.of(2L));

        SysRole entity = new SysRole();
        entity.setId(10L);
        entity.setMenuIds(List.of(1L));
        entity.setDeptIds(List.of(2L));
        when(roleConverter.updateDtoToEntity(dto)).thenReturn(entity);
        when(roleMapper.updateById(any(SysRole.class))).thenReturn(1);
        when(roleMenuService.remove(any(Wrapper.class))).thenReturn(true);
        when(roleMenuService.saveBatch(any())).thenReturn(true);
        when(roleDeptService.remove(any(Wrapper.class))).thenReturn(true);
        when(roleDeptService.saveBatch(any())).thenReturn(true);

        int result = roleService.updateRole(dto);

        assertThat(result).isEqualTo(1);
        // 先清旧关联，再建新关联
        verify(roleMenuService).remove(any(Wrapper.class));
        verify(roleDeptService).remove(any(Wrapper.class));
        verify(roleMenuService).saveBatch(any());
        verify(roleDeptService).saveBatch(any());
    }

    @Test
    void shouldDeleteRolesAndRelations() {
        SysUserRole link = new SysUserRole();
        link.setUserId(100L);
        link.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(link));
        when(roleMenuService.remove(any(Wrapper.class))).thenReturn(true);
        when(roleDeptService.remove(any(Wrapper.class))).thenReturn(true);
        when(sysUserRoleMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(roleMapper.deleteByIds(any())).thenReturn(2);

        int result = roleService.deleteRoleByIds(new Long[] {1L, 2L});

        assertThat(result).isEqualTo(1);
        // 批量删除：菜单、数据权限、用户-角色关联各清一次
        verify(roleMenuService).remove(any(Wrapper.class));
        verify(roleDeptService).remove(any(Wrapper.class));
        verify(sysUserRoleMapper).delete(any(Wrapper.class));
        // 持有被删角色的用户必须收到权限缓存失效广播
        verify(redisService).publish("auth:refresh:channel", "user:100");
    }

    @Test
    void shouldDeleteRolesWithoutBroadcastWhenNoUsers() {
        when(sysUserRoleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(roleMenuService.remove(any(Wrapper.class))).thenReturn(true);
        when(roleDeptService.remove(any(Wrapper.class))).thenReturn(true);
        when(sysUserRoleMapper.delete(any(Wrapper.class))).thenReturn(0);
        when(roleMapper.deleteByIds(any())).thenReturn(1);

        int result = roleService.deleteRoleByIds(new Long[] {9L});

        assertThat(result).isEqualTo(1);
        verify(redisService, never()).publish(any(), any());
    }
}