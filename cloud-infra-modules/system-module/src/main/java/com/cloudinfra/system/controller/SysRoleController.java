package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.service.ISysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final ISysRoleService sysRoleService;

    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(sysRoleService.list());
    }

    @PostMapping
    public Result<?> add(@RequestBody SysRole role) {
        return Result.success(sysRoleService.save(role));
    }
}
