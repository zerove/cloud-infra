package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "部门管理")
@RestController
@RequestMapping("/system/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final ISysDeptService deptService;

    @Operation(summary = "查询部门列表")
    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    @GetMapping("/list")
    public Result<List<SysDept>> list(SysDept dept) {
        List<SysDept> depts = deptService.selectDeptList(dept);
        return Result.success(depts);
    }

    @Operation(summary = "新增部门")
    @PreAuthorize("@ss.hasPermi('system:dept:add')")
    @Log(title = "部门管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysDept dept) {
        return deptService.insertDept(dept) ? Result.success() : Result.fail(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode(), "新增部门失败");
    }

    @Operation(summary = "修改部门")
    @PreAuthorize("@ss.hasPermi('system:dept:edit')")
    @Log(title = "部门管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysDept dept) {
        return deptService.updateDept(dept) ? Result.success() : Result.fail(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode(), "修改部门失败");
    }

    @Operation(summary = "删除部门")
    @PreAuthorize("@ss.hasPermi('system:dept:remove')")
    @Log(title = "部门管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{deptId}")
    public Result<Void> remove(@PathVariable Long deptId) {
        return deptService.deleteDeptById(deptId) ? Result.success() : Result.fail(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode(), "删除部门失败");
    }
}
