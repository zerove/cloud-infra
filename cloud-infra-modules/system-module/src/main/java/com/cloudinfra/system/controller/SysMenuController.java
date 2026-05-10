package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.service.ISysMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "菜单管理")
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final ISysMenuService menuService;

    @Operation(summary = "查询菜单列表")
    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    @GetMapping("/list")
    public Result<List<SysMenu>> list(SysMenu menu) {
        List<SysMenu> menus = menuService.selectMenuList(menu);
        return Result.success(menus);
    }

    @Operation(summary = "新增菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:add')")
    @Log(title = "菜单管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysMenu menu) {
        return menuService.save(menu) ? Result.success() : Result.error("新增菜单失败");
    }

    @Operation(summary = "修改菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    @Log(title = "菜单管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysMenu menu) {
        return menuService.updateById(menu) ? Result.success() : Result.error("修改菜单失败");
    }

    @Operation(summary = "删除菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:remove')")
    @Log(title = "菜单管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{menuId}")
    public Result<Void> remove(@PathVariable Long menuId) {
        return menuService.removeById(menuId) ? Result.success() : Result.error("删除菜单失败");
    }
}
