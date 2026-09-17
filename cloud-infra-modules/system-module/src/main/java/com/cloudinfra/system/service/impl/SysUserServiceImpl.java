package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.ISysConfigService;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.exception.SystemBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements ISysUserService {
    
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final ISysConfigService configService;
    private final ISysPermissionService permissionService;
    private final UserSessionRevoker sessionRevoker;
    private final PermissionInvalidationService permissionInvalidationService;

    @Override
    public IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user) {
        return userMapper.selectUserPage(page, user);
    }

    @Override
    public SysUser selectUserById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId))
            .orElseThrow(() -> new SystemBusinessException(com.cloudinfra.common.core.exception.SystemExceptionCode.USER_NOT_FOUND));
    }

    @Override
    public SysUser selectUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    @Override
    public LoginUser getLoginUserById(Long userId) {
        SysUser user = selectUserById(userId);

        // 加载角色（含数据权限范围）
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream()
            .map(SysUserRole::getRoleId)
            .collect(Collectors.toList());

        List<SysRole> roles = roleIds.isEmpty()
            ? List.of()
            : roleMapper.selectBatchIds(roleIds);

        return LoginUser.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .deptId(user.getDeptId())
            .roleId(user.getRoleId())
            .roles(roles)
            .permissions(new java.util.HashSet<>(permissionService.getPermissionsByUserId(userId)))
            .build();
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertUser(SysUserCreateDTO userDTO) {
        SysUser user = prepareNewUser(userDTO);
        String initPassword = configService.selectConfigByKey("sys.user.initPassword");
        user.initializePassword(passwordEncoder, initPassword);
        return userMapper.insert(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUserReturningId(SysUserCreateDTO userDTO) {
        SysUser user = prepareNewUser(userDTO);
        // 新接口遵循请求中的密码，不覆盖为管理端初始化密码。
        user.updatePassword(passwordEncoder, userDTO.getPassword());
        if (userMapper.insert(user) != 1 || user.getUserId() == null) {
            throw new IllegalStateException("User creation did not return a generated ID");
        }
        return user.getUserId();
    }

    private SysUser prepareNewUser(SysUserCreateDTO userDTO) {
        // 1. 业务唯一性校验
        if (selectUserByUsername(userDTO.getUsername()) != null) {
            throw new SystemBusinessException("用户名 [" + userDTO.getUsername() + "] 已存在，请更换");
        }

        // 2. 转换为 Entity
        SysUser user = new SysUser();
        user.setUsername(userDTO.getUsername());
        user.setNickName(userDTO.getNickName());
        user.setPassword(userDTO.getPassword());
        user.setDeptId(userDTO.getDeptId());
        user.setRoleId(userDTO.getRoleId());
        user.setPostId(userDTO.getPostId());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());

        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int registerUser(com.cloudinfra.api.system.dto.RegisterUserDTO userDTO) {
        // 业务唯一性校验
        if (selectUserByUsername(userDTO.getUsername()) != null) {
            throw new SystemBusinessException("用户名 [" + userDTO.getUsername() + "] 已存在，请更换");
        }

        SysUser user = new SysUser();
        user.setUsername(userDTO.getUsername());
        user.setNickName(userDTO.getNickName());
        user.setDeptId(userDTO.getDeptId());
        user.setRoleId(userDTO.getRoleId());
        user.setPostId(userDTO.getPostId());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        // 注册时使用请求明文密码（不覆盖为系统初始化默认密码）
        user.updatePassword(passwordEncoder, userDTO.getPassword());
        return userMapper.insert(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateUser(SysUserUpdateDTO userDTO) {
        SysUser user = new SysUser();
        user.setUserId(userDTO.getUserId());
        user.setNickName(userDTO.getNickName());
        user.setDeptId(userDTO.getDeptId());
        user.setRoleId(userDTO.getRoleId());
        user.setPostId(userDTO.getPostId());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setStatus(userDTO.getStatus());
        
        int rows = userMapper.updateById(user);
        // 角色变更后通知下游失效该用户权限缓存
        if (userDTO.getRoleId() != null) {
            permissionInvalidationService.invalidateUser(userDTO.getUserId());
        }
        // 停用（status=1）时立即吊销存量会话，防止被停用账号继续使用已签发 JWT
        if (userDTO.getStatus() != null && userDTO.getStatus() == 1) {
            sessionRevoker.revokeAllSessions(userDTO.getUserId());
        }
        return rows;
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUserByIds(Long[] userIds) {
        // 先清理用户-角色关联再删用户；同时广播失效权限缓存
        for (Long userId : userIds) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
            permissionInvalidationService.invalidateUser(userId);
        }
        int rows = userMapper.deleteBatchIds(List.of(userIds));
        // 删除后吊销全部存量会话，已签发 token 立即失效
        sessionRevoker.revokeAllSessions(List.of(userIds));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resetUserPassword(Long userId, String password) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.updatePassword(passwordEncoder, password);
        int rows = userMapper.updateById(user);
        // 密码重置后吊销全部会话：持有旧凭据的攻击者/前用户必须重新登录
        sessionRevoker.revokeAllSessions(userId);
        return rows;
    }

    }
