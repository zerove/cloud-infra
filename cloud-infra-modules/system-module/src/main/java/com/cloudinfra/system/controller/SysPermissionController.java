package com.cloudinfra.system.controller;

import com.cloudinfra.common.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPermissionEntity;
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
    public List<SysPermissionEntity> list() {
        return sysPermissionService.list();
    }

    @AuditLog(module = "PERMISSION", action = "ADD")
    @PostMapping
    public Result<Void> add(@RequestBody SysPermissionEntity permission) {
        sysPermissionService.save(permission);
        return Result.success();
    }
}