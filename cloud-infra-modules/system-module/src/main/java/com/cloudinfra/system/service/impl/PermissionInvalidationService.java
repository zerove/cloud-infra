package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudinfra.common.core.security.PermissionRefreshProtocol;
import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 权限缓存失效收口服务：所有 RBAC 变更路径必须经由本类广播失效消息，
 * 禁止业务代码直接 publish 到 {@link PermissionRefreshProtocol#CHANNEL}。
 *
 * <p>设计动机：失效协议是"变更方先查受影响用户、再逐个展开 user:{uid} 消息"
 * 的人肉约定，deleteRoleByIds 曾因此漏掉广播。收口后查询逻辑只存在一份，
 * 新增变更路径默认继承正确行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionInvalidationService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final RedisService redisService;

    /** 单个用户权限/上下文变更后失效其缓存。 */
    public void invalidateUser(Long userId) {
        if (userId == null) {
            return;
        }
        redisService.publish(PermissionRefreshProtocol.CHANNEL, PermissionRefreshProtocol.userMessage(userId));
    }

    /** 批量用户失效。 */
    public void invalidateUsers(List<Long> userIds) {
        if (userIds == null) {
            return;
        }
        userIds.stream().distinct().forEach(this::invalidateUser);
    }

    /**
     * 角色级变更：查出持有该角色的全部用户并逐一失效。
     * 必须在角色关联被删除前调用，否则查不到受影响用户。
     */
    public void invalidateRole(Long roleId) {
        if (roleId == null) {
            return;
        }
        List<Long> userIds = findUserIdsByRoleId(roleId);
        log.info("角色变更触发权限失效, roleId={}, 受影响用户数={}", roleId, userIds.size());
        invalidateUsers(userIds);
    }

    /** 批量角色失效（同样须在关联删除前调用）。 */
    public void invalidateRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<Long> userIds = sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().in("role_id", roleIds))
            .stream().map(SysUserRole::getUserId).distinct().collect(Collectors.toList());
        log.info("角色批量变更触发权限失效, roleIds={}, 受影响用户数={}", roleIds.size(), userIds.size());
        invalidateUsers(userIds);
    }

    private List<Long> findUserIdsByRoleId(Long roleId) {
        return sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("role_id", roleId))
            .stream().map(SysUserRole::getUserId).distinct().collect(Collectors.toList());
    }
}
