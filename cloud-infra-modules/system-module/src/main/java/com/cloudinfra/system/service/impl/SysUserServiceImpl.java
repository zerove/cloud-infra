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
import io.micrometer.tracing.annotation.NewSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    private final SysUserConverter sysUserConverter;

    @Override
    @NewSpan("getPageList")
    public PageResult<SysUserVO> getPageList(SysUserDTO dto) {
        Page<SysUser> page = page(new Page<>(dto.getCurrent(), dto.getSize()),
                new LambdaQueryWrapper<SysUser>()
                        .like(dto.getUsername() != null, SysUser::getUsername, dto.getUsername())
                        .eq(dto.getStatus() != null, SysUser::getStatus, dto.getStatus()));

        return PageResult.of(
                page.getRecords().stream().map(sysUserConverter::entityToVo).collect(Collectors.toList()),
                page.getTotal()
        );
    }

    @Override
    @NewSpan("addUser")
    public boolean addUser(SysUserDTO dto) {
        SysUser user = sysUserConverter.dtoToEntity(dto);
        user.setPassword(com.cloudinfra.framework.security.utils.SecurityUtils.encryptPassword(dto.getPassword()));
        return save(user);
    }

    @Override
    public SysUser getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }
}
