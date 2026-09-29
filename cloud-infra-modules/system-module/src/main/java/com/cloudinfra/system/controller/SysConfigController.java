package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysConfig;
import com.cloudinfra.system.service.ISysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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


@Tag(name = "参数配置管理")
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final ISysConfigService configService;

    @Operation(summary = "查询参数配置列表")
    @PreAuthorize("hasAuthority('system:config:list')")
    @GetMapping("/list")
    public IPage<SysConfig> list(SysConfig query,
                                 @RequestParam(defaultValue = "1") long pageNum,
                                 @RequestParam(defaultValue = "10") long pageSize) {
        return configService.selectConfigList(query, pageNum, pageSize);
    }

    @Operation(summary = "查询参数配置详细")
    @PreAuthorize("hasAuthority('system:config:query')")
    @GetMapping("/{id}")
    public SysConfig getConfig(@PathVariable Long id) {
        return configService.selectConfigById(id);
    }

    @Operation(summary = "根据参数键名查询参数值")
    @PreAuthorize("hasAuthority('system:config:query')")
    @GetMapping("/configKey/{configKey}")
    public String getConfigKey(@PathVariable String configKey) {
        return configService.selectConfigByKey(configKey);
    }

    @Operation(summary = "新增参数配置")
    @PreAuthorize("hasAuthority('system:config:add')")
    @AuditLog(module = "CONFIG", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysConfig config) {
        configService.insertConfig(config);
        return Result.success();
    }

    @Operation(summary = "修改参数配置")
    @PreAuthorize("hasAuthority('system:config:edit')")
    @AuditLog(module = "CONFIG", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysConfig config) {
        configService.updateConfig(config);
        return Result.success();
    }

    @Operation(summary = "删除参数配置")
    @PreAuthorize("hasAuthority('system:config:remove')")
    @AuditLog(module = "CONFIG", action = "DELETE")
    @DeleteMapping("/{ids}")
    public Result<Void> remove(@PathVariable Long[] ids) {
        configService.deleteConfigByIds(ids);
        return Result.success();
    }
}
