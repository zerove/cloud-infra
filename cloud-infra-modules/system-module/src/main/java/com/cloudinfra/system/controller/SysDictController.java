package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.service.ISysDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
    public Result<List<SysDictType>> listTypes() {
        return Result.success(dictService.selectDictTypeList());
    }

    @Operation(summary = "查询字典类型详细")
    @PreAuthorize("hasAuthority('system:dict:query')")
    @GetMapping("/type/{id}")
    public Result<SysDictType> getType(@PathVariable Long id) {
        return Result.success(dictService.selectDictTypeById(id));
    }

    @Operation(summary = "新增字典类型")
    @PreAuthorize("hasAuthority('system:dict:add')")
    @Log(title = "字典类型", businessType = BusinessType.INSERT)
    @PostMapping("/type")
    public Result<Void> addType(@RequestBody SysDictType dictType) {
        dictService.insertDictType(dictType);
        return Result.success();
    }

    @Operation(summary = "修改字典类型")
    @PreAuthorize("hasAuthority('system:dict:edit')")
    @Log(title = "字典类型", businessType = BusinessType.UPDATE)
    @PutMapping("/type")
    public Result<Void> editType(@RequestBody SysDictType dictType) {
        dictService.updateDictType(dictType);
        return Result.success();
    }

    @Operation(summary = "删除字典类型")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @Log(title = "字典类型", businessType = BusinessType.DELETE)
    @DeleteMapping("/type/{ids}")
    public Result<Void> removeType(@PathVariable Long[] ids) {
        dictService.deleteDictTypeByIds(ids);
        return Result.success();
    }

    @Operation(summary = "根据字典类型查询字典数据")
    @GetMapping("/data/type/{dictType}")
    public Result<List<SysDictData>> dictDataByType(@PathVariable String dictType) {
        return Result.success(dictService.selectDictDataByType(dictType));
    }

    @Operation(summary = "新增字典数据")
    @PreAuthorize("hasAuthority('system:dict:add')")
    @Log(title = "字典数据", businessType = BusinessType.INSERT)
    @PostMapping("/data")
    public Result<Void> addData(@RequestBody SysDictData dictData) {
        dictService.insertDictData(dictData);
        return Result.success();
    }

    @Operation(summary = "修改字典数据")
    @PreAuthorize("hasAuthority('system:dict:edit')")
    @Log(title = "字典数据", businessType = BusinessType.UPDATE)
    @PutMapping("/data")
    public Result<Void> editData(@RequestBody SysDictData dictData) {
        dictService.updateDictData(dictData);
        return Result.success();
    }

    @Operation(summary = "删除字典数据")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @Log(title = "字典数据", businessType = BusinessType.DELETE)
    @DeleteMapping("/data/{ids}")
    public Result<Void> removeData(@PathVariable Long[] ids) {
        dictService.deleteDictDataByIds(ids);
        return Result.success();
    }

    @Operation(summary = "清除字典缓存")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    @Log(title = "字典插件", businessType = BusinessType.CLEAN)
    @DeleteMapping("/type/clearCache")
    public Result<Void> clearCache() {
        dictService.clearDictCache();
        return Result.success();
    }
}
