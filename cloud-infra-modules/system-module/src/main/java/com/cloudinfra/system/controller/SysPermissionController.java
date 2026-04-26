package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPermission;
import com.cloudinfra.system.service.ISysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/permission")
@RequiredArgsConstructor
public class SysPermissionController {

    private final ISysPermissionService sysPermissionService;

    @GetMapping("/list")
    public Result<List<SysPermission>> list() {
        return Result.success(sysPermissionService.list());
    }

    @PostMapping
    public Result<?> add(@RequestBody SysPermission permission) {
        return Result.success(sysPermissionService.save(permission));
    }
}
