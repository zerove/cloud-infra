package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;

public interface ISysUserService {
    IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user);
    SysUser selectUserById(Long userId);
    SysUser selectUserByUsername(String username);
    int insertUser(SysUserCreateDTO userDTO);
    int updateUser(SysUserUpdateDTO userDTO);
    int deleteUserByIds(Long[] userIds);
    int resetUserPassword(Long userId, String password);

    void verifySeataTransaction(Long userId, String newNickName);
}
