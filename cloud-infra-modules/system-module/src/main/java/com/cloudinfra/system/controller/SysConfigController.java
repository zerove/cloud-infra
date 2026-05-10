package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysConfig;
import com.cloudinfra.system.service.ISysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "参数配置")
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final ISysConfigService configService;

    @Operation(summary = "查询参数列表")
    @GetMapping("/list")
    public Result<List<SysConfig>> list() {
        return Result.success(configService.selectConfigList());
    }

    @Operation(summary = "根据键名查询参数值")
    @GetMapping("/key/{configKey}")
    public Result<String> getConfigKey(@PathVariable String configKey) {
        return Result.success(configService.selectConfigByKey(configKey));
    }

    @Operation(summary = "新增参数配置")
    @PostMapping
    public Result<Void> add(@RequestBody SysConfig config) {
        configService.insertConfig(config);
        return Result.success();
    }

    @Operation(summary = "修改参数配置")
    @PutMapping
    public Result<Void> edit(@RequestBody SysConfig config) {
        configService.updateConfig(config);
        return Result.success();
    }
}
