package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.service.ISysDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "字典管理")
@RestController
@RequestMapping("/system/dict")
@RequiredArgsConstructor
public class SysDictController {

    private final ISysDictService dictService;

    @Operation(summary = "查询字典类型列表")
    @PreAuthorize("hasAuthority('system:dict:list')")
    @GetMapping("/type/list")
    public List<SysDictType> listTypes() {
        return dictService.selectDictTypeList();
    }

    @Operation(summary = "查询字典类型详细")
    @PreAuthorize("hasAuthority('system:dict:query')")
    @GetMapping("/type/{id}")
    public SysDictType getType(@PathVariable Long id) {
        return dictService.selectDictTypeById(id);
    }

    @Operation(summary = "新增字典类型")
    @PreAuthorize("hasAuthority('system:dict:add')")
    @AuditLog(module = "DICT_TYPE", action = "ADD")
    @PostMapping("/type")
    public Result<Void> addType(@Valid @RequestBody SysDictType dictType) {
        dictService.insertDictType(dictType);
        return Result.success();
    }

    @Operation(summary = "修改字典类型")
    @PreAuthorize("hasAuthority('system:dict:edit')")
    @AuditLog(module = "DICT_TYPE", action = "EDIT")
    @PutMapping("/type")
    public Result<Void> editType(@Valid @RequestBody SysDictType dictType) {
        dictService.updateDictType(dictType);
        return Result.success();
    }

    @Operation(summary = "删除字典类型")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @AuditLog(module = "DICT_TYPE", action = "DELETE")
    @DeleteMapping("/type/{ids}")
    public Result<Void> removeType(@PathVariable Long[] ids) {
        dictService.deleteDictTypeByIds(ids);
        return Result.success();
    }

    @Operation(summary = "根据字典类型查询字典数据")
    @PreAuthorize("hasAuthority('system:dict:query')")
    @GetMapping("/data/type/{dictType}")
    public List<SysDictData> dictDataByType(@PathVariable String dictType) {
        return dictService.selectDictDataByType(dictType);
    }

    @Operation(summary = "新增字典数据")
    @PreAuthorize("hasAuthority('system:dict:add')")
    @AuditLog(module = "DICT_DATA", action = "ADD")
    @PostMapping("/data")
    public Result<Void> addData(@Valid @RequestBody SysDictData dictData) {
        dictService.insertDictData(dictData);
        return Result.success();
    }

    @Operation(summary = "修改字典数据")
    @PreAuthorize("hasAuthority('system:dict:edit')")
    @AuditLog(module = "DICT_DATA", action = "EDIT")
    @PutMapping("/data")
    public Result<Void> editData(@Valid @RequestBody SysDictData dictData) {
        dictService.updateDictData(dictData);
        return Result.success();
    }

    @Operation(summary = "删除字典数据")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @AuditLog(module = "DICT_DATA", action = "DELETE")
    @DeleteMapping("/data/{ids}")
    public Result<Void> removeData(@PathVariable Long[] ids) {
        dictService.deleteDictDataByIds(ids);
        return Result.success();
    }

    @Operation(summary = "清除字典缓存")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @AuditLog(module = "DICT_CACHE", action = "CLEAR")
    @DeleteMapping("/type/clearCache")
    public Result<Void> clearCache() {
        dictService.clearDictCache();
        return Result.success();
    }
}
