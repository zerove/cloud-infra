package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import com.cloudinfra.common.core.domain.Result;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/dept")
public class SysDeptController {

    @Autowired
    private ISysDeptService deptService;

    @GetMapping("/list")
    public Result<List<SysDept>> list(@RequestParam(required = false) Long parentId) {
        return Result.success(deptService.selectDeptList(parentId));
    }

    @PreAuthorize("@ss.hasPermission('system:dept:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysDept dept) {
        deptService.insertDept(dept);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:dept:remove')")
    @DeleteMapping("/{deptId}")
    public Result<Void> remove(@PathVariable Long deptId) {
        deptService.deleteDeptById(deptId);
        return Result.success();
    }
}
