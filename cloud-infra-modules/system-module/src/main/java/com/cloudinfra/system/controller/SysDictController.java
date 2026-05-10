package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysDictData;
import com.cloudinfra.system.domain.SysDictType;
import com.cloudinfra.system.service.ISysDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "字典管理")
@RestController
@RequestMapping("/dict")
@RequiredArgsConstructor
public class SysDictController {

    private final ISysDictService dictService;

    @Operation(summary = "查询字典类型列表")
    @GetMapping("/type/list")
    public Result<List<SysDictType>> listTypes() {
        return Result.success(dictService.selectDictTypeList());
    }

    @Operation(summary = "根据字典类型查询字典数据")
    @GetMapping("/data/type/{dictType}")
    public Result<List<SysDictData>> dictDataByType(@PathVariable String dictType) {
        return Result.success(dictService.selectDictDataByType(dictType));
    }

    @Operation(summary = "新增字典类型")
    @PostMapping("/type")
    public Result<Void> addType(@RequestBody SysDictType dictType) {
        dictService.insertDictType(dictType);
        return Result.success();
    }

    @Operation(summary = "修改字典数据")
    @PutMapping("/data")
    public Result<Void> editData(@RequestBody SysDictData dictData) {
        dictService.updateDictData(dictData);
        return Result.success();
    }
}
