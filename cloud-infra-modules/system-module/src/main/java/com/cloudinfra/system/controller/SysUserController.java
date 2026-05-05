package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.annotation.Log;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Management", description = "Operations related to system users")
@RestController
@RequestMapping("/system/user")
public class SysUserController {

    @Autowired
    private ISysUserService userService;

    @Log(title = "用户查询", businessType = "QUERY")
    @GetMapping("/list")
    @Operation(summary = "List users", description = "Retrieve a paginated list of users based on search criteria")
    public Object list(Page<SysUser> page, SysUser user) {
        return userService.selectUserList(page, user);
    }
}
