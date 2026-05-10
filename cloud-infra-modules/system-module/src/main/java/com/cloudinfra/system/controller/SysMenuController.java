package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService sysMenuService;

    @PreAuthorize("@ss.hasPermission('system:menu:list')")
    @GetMapping("/tree")
    public Result<List<SysMenu>> treeList() {
        return Result.success(sysMenuService.treeList());
    }

    @PreAuthorize("@ss.hasPermission('system:menu:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysMenu menu) {
        sysMenuService.addMenu(menu);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:menu:edit')")
    @PutMapping
    public Result<Void> edit(@RequestBody SysMenu menu) {
        sysMenuService.updateMenu(menu);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('system:menu:remove')")
    public Result<Void> remove(@PathVariable Long id) {
        sysMenuService.deleteMenu(id);
        return Result.success();
    }
}
