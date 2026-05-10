package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.system.domain.SysConfig;
import com.cloudinfra.system.service.ISysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "参数配置管理")
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final ISysConfigService configService;

    @Operation(summary = "查询参数配置列表")
    @PreAuthorize("hasAuthority('system:config:list')")
    @GetMapping("/list")
    public Result<List<SysConfig>> list() {
        return Result.success(configService.selectConfigList());
    }

    @Operation(summary = "查询参数配置详细")
    @PreAuthorize("hasAuthority('system:config:query')")
    @GetMapping("/{id}")
    public Result<SysConfig> getConfig(@PathVariable Long id) {
        return Result.success(configService.selectConfigById(id));
    }

    @Operation(summary = "根据参数键名查询参数值")
    @GetMapping("/configKey/{configKey}")
    public Result<String> getConfigKey(@PathVariable String configKey) {
        return Result.success(configService.selectConfigByKey(configKey));
    }

    @Operation(summary = "新增参数配置")
    @PreAuthorize("hasAuthority('system:config:add')")
    @Log(title = "参数管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysConfig config) {
        configService.insertConfig(config);
        return Result.success();
    }

    @Operation(summary = "修改参数配置")
    @PreAuthorize("hasAuthority('system:config:edit')")
    @Log(title = "参数管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysConfig config) {
        configService.updateConfig(config);
        return Result.success();
    }

    @Operation(summary = "删除参数配置")
    @PreAuthorize("hasAuthority('system:config:remove')")
    @Log(title = "参数管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public Result<Void> remove(@PathVariable Long[] ids) {
        configService.deleteConfigByIds(ids);
        return Result.success();
    }
}
