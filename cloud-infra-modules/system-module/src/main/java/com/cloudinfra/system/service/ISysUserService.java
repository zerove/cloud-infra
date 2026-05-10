package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.system.domain.SysUser;

public interface ISysUserService {
    @DataScope(deptAlias = "d")
    IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user);
}
