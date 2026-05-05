package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.annotation.Log;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/user")
public class SysUserController {

    @Autowired
    private ISysUserService userService;

    @Log(title = "用户查询", businessType = "QUERY")
    @GetMapping("/list")
    public Object list(Page<SysUser> page, SysUser user) {
        return userService.selectUserList(page, user);
    }
}
