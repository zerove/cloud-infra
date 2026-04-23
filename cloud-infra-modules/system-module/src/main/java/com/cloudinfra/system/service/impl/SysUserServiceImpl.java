package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.framework.security.utils.SecurityUtils;
import com.cloudinfra.system.converter.SysUserConverter;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    private final SysUserConverter sysUserConverter;

    @Override
    public PageResult<SysUserVO> getPageList(SysUserDTO dto) {
        Page<SysUser> page = new Page<>(dto.getCurrent(), dto.getSize());
        // 实际开发中可配合 QueryWrapper 构建复杂条件
        Page<SysUser> resultPage = this.page(page);
        
        List<SysUserVO> voList = sysUserConverter.entityListToVoList(resultPage.getRecords());
        return PageResult.of(voList, resultPage.getTotal());
    }

    @Override
    public boolean addUser(SysUserDTO dto) {
        SysUser user = sysUserConverter.dtoToEntity(dto);
        if (user.getPassword() != null) {
            user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        }
        return this.save(user);
    }

    @Override
    public SysUser getByUsername(String username) {
        return this.lambdaQuery().eq(SysUser::getUsername, username).one();
    }
}
