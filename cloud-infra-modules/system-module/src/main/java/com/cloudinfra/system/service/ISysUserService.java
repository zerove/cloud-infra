package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;

public interface ISysUserService extends IService<SysUser> {
    
    PageResult<SysUserVO> getPageList(SysUserDTO dto);
    
    boolean addUser(SysUserDTO dto);

    SysUser getByUsername(String username);
}
