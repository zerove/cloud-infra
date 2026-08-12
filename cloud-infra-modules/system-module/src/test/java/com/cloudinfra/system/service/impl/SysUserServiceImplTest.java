package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.service.ISysConfigService;
import com.cloudinfra.system.service.ISysPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户服务单元测试
 * 覆盖：查询、创建（密码初始化）、注册、更新、删除、重置密码、登录用户装配
 */
@ExtendWith(MockitoExtension.class)
public class SysUserServiceImplTest {

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ISysConfigService configService;

    @Mock
    private ISysPermissionService permissionService;

    @InjectMocks
    private SysUserServiceImpl userService;

    @BeforeEach
    void setUp() {
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENCODED:" + inv.getArgument(0));
    }

    @Test
    void shouldSelectUserById() {
        SysUser expected = new SysUser();
        expected.setUserId(1L);
        expected.setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(expected);

        SysUser actual = userService.selectUserById(1L);

        assertThat(actual.getUserId()).isEqualTo(1L);
        assertThat(actual.getUsername()).isEqualTo("admin");
    }

    @Test
    void shouldThrowWhenUserNotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> userService.selectUserById(999L))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("用户不存在");
    }

    @Test
    void shouldSelectUserListPaginated() {
        IPage<SysUser> page = new Page<>(1, 10);
        when(userMapper.selectUserPage(any(Page.class), any(SysUser.class))).thenReturn(page);

        IPage<SysUser> result = userService.selectUserList(new Page<>(1, 10), new SysUser());

        assertThat(result).isSameAs(page);
    }

    @Test
    void shouldRejectDuplicateUsernameOnInsert() {
        SysUserCreateDTO dto = new SysUserCreateDTO();
        dto.setUsername("admin");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new SysUser());

        assertThatThrownBy(() -> userService.insertUser(dto))
            .isInstanceOf(SystemBusinessException.class)
            .hasMessageContaining("已存在");

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void shouldInsertUserWithInitializedPassword() {
        SysUserCreateDTO dto = new SysUserCreateDTO();
        dto.setUsername("newuser");
        dto.setNickName("新用户");
        dto.setDeptId(2L);
        dto.setRoleId(3L);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(configService.selectConfigByKey("sys.user.initPassword")).thenReturn("Init@123");
        when(userMapper.insert(any(SysUser.class))).thenReturn(1);

        int result = userService.insertUser(dto);

        assertThat(result).isEqualTo(1);
        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("ENCODED:Init@123");
    }

    @Test
    void shouldRegisterUserWithRequestPassword() {
        com.cloudinfra.api.system.dto.RegisterUserDTO dto = new com.cloudinfra.api.system.dto.RegisterUserDTO();
        dto.setUsername("registeruser");
        dto.setPassword("Plain@123");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenReturn(1);

        int result = userService.registerUser(dto);

        assertThat(result).isEqualTo(1);
        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("ENCODED:Plain@123");
        // 注册不使用系统初始化密码
        verify(configService, never()).selectConfigByKey(anyString());
    }

    @Test
    void shouldUpdateUser() {
        SysUserUpdateDTO dto = new SysUserUpdateDTO();
        dto.setUserId(1L);
        dto.setNickName("改名");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        int result = userService.updateUser(dto);

        assertThat(result).isEqualTo(1);
        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    void shouldDeleteUsersByIds() {
        when(userMapper.deleteBatchIds(any(List.class))).thenReturn(2);

        int result = userService.deleteUserByIds(new Long[] {1L, 2L});

        assertThat(result).isEqualTo(2);
    }

    @Test
    void shouldResetUserPassword() {
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        int result = userService.resetUserPassword(1L, "New@123");

        assertThat(result).isEqualTo(1);
        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("ENCODED:New@123");
    }

    @Test
    void shouldBuildLoginUserWithRolesAndPermissions() {
        SysUser user = new SysUser();
        user.setUserId(5L);
        user.setUsername("zhangsan");
        user.setDeptId(2L);
        when(userMapper.selectById(5L)).thenReturn(user);

        SysUserRole rel1 = new SysUserRole();
        rel1.setUserId(5L);
        rel1.setRoleId(10L);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rel1));

        SysRole role = new SysRole();
        role.setId(10L);
        role.setRoleKey("admin");
        when(roleMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(role));

        when(permissionService.getPermissionsByUserId(5L)).thenReturn(List.of("system:user:list"));

        LoginUser loginUser = userService.getLoginUserById(5L);

        assertThat(loginUser.getUserId()).isEqualTo(5L);
        assertThat(loginUser.getRoles()).extracting(SysRole::getRoleKey).containsExactly("admin");
        assertThat(loginUser.getPermissions()).isEqualTo(Set.of("system:user:list"));
    }

    @Test
    void shouldBuildLoginUserWithEmptyRolesWhenNoRelations() {
        SysUser user = new SysUser();
        user.setUserId(6L);
        user.setUsername("nobody");
        when(userMapper.selectById(6L)).thenReturn(user);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        LoginUser loginUser = userService.getLoginUserById(6L);

        assertThat(loginUser.getRoles()).isEmpty();
        assertThat(loginUser.getPermissions()).isEmpty();
    }
}