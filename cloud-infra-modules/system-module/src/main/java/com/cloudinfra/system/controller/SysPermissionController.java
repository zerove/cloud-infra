package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.system.service.ISysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/system/permission")
@RequiredArgsConstructor
public class SysPermissionController {

    private final ISysPermissionService sysPermissionService;

    @GetMapping("/list")
    public IPage<SysPermissionEntity> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return sysPermissionService.list(pageNum, pageSize);
    }

    @AuditLog(module = "PERMISSION", action = "ADD")
    @PreAuthorize("@ss.hasPermi('system:permission:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysPermissionEntity permission) {
        sysPermissionService.save(permission);
        return Result.success();
    }
}