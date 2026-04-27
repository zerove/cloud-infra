package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.system.converter.SysUserConverter;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.common.util.SecurityUtils;
import io.micrometer.tracing.annotation.NewSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

import com.cloudinfra.system.service.ISysPermissionService;
import java.util.List;
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    private final SysUserConverter sysUserConverter;
    private final ISysPermissionService sysPermissionService;

    @Override
    public List<SysUserVO> listUsers() {
        return list().stream().map(user -> {
            SysUserVO vo = sysUserConverter.entityToVo(user);
            vo.setPermissions(sysPermissionService.getPermissionsByUserId(user.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @NewSpan("getPageList")
    public PageResult<SysUserVO> getPageList(SysUserDTO dto) {
        Page<SysUser> page = page(new Page<>(dto.getCurrent(), dto.getSize()),
                new LambdaQueryWrapper<SysUser>()
                        .like(dto.getUsername() != null, SysUser::getUsername, dto.getUsername())
                        .eq(dto.getStatus() != null, SysUser::getStatus, dto.getStatus()));

        return PageResult.of(
                page.getRecords().stream().map(user -> {
                    SysUserVO vo = sysUserConverter.entityToVo(user);
                    vo.setPermissions(sysPermissionService.getPermissionsByUserId(user.getId()));
                    return vo;
                }).collect(Collectors.toList()),
                page.getTotal()
        );
    }

    @Override
    @NewSpan("addUser")
    public boolean addUser(SysUserDTO dto) {
        SysUser user = sysUserConverter.dtoToEntity(dto);
        user.setPassword(SecurityUtils.encryptPassword(dto.getPassword()));
        boolean success = save(user);
        if (success) {
            sysPermissionService.clearPermissionCache(user.getId());
        }
        return success;
    }

    @Override
    public SysUser getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }
}
