package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.vo.SysDeptVO;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.service.ISysDeptService;
import com.cloudinfra.system.converter.SysDeptConverter;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "部门管理")
@RestController
@RequestMapping("/system/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final ISysDeptService deptService;
    private final SysDeptConverter deptConverter;

    @Operation(summary = "查询部门列表")
    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    @GetMapping("/list")
    public IPage<SysDeptVO> list(SysDept dept, 
                                 @RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysDept> page = new Page<>(pageNum, pageSize);
        IPage<SysDept> depts = deptService.selectDeptList(page, dept);
        return depts.convert(deptConverter::entityToVo);
    }

    @Operation(summary = "新增部门")
    @PreAuthorize("@ss.hasPermi('system:dept:add')")
    @AuditLog(module = "DEPT", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysDeptCreateDTO deptDTO) {
        deptService.insertDept(deptDTO);
        return Result.success();
    }

    @Operation(summary = "修改部门")
    @PreAuthorize("@ss.hasPermi('system:dept:edit')")
    @AuditLog(module = "DEPT", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysDeptUpdateDTO deptDTO) {
        deptService.updateDept(deptDTO);
        return Result.success();
    }

    @Operation(summary = "删除部门")
    @PreAuthorize("@ss.hasPermi('system:dept:remove')")
    @AuditLog(module = "DEPT", action = "DELETE")
    @DeleteMapping("/{deptId}")
    public Result<Void> remove(@PathVariable Long deptId) {
        deptService.deleteDeptById(deptId);
        return Result.success();
    }
}