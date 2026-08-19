package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;

public interface ISysUserService {
    IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user);
    SysUser selectUserById(Long userId);
    SysUser selectUserByUsername(String username);

    /**
     * 加载用户完整安全上下文（含部门、角色数据权限、权限标识）
     */
    LoginUser getLoginUserById(Long userId);

    int insertUser(SysUserCreateDTO userDTO);

    /**
     * 注册用户（公开端点，明文密码由本服务加密）
     */
    int registerUser(com.cloudinfra.api.system.dto.RegisterUserDTO userDTO);

    int updateUser(SysUserUpdateDTO userDTO);
    int deleteUserByIds(Long[] userIds);
    int resetUserPassword(Long userId, String password);
}
