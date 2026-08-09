package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysMenu;
import com.cloudinfra.system.domain.vo.SysMenuVO;
import com.cloudinfra.system.domain.dto.SysMenuCreateDTO;
import com.cloudinfra.system.domain.dto.SysMenuUpdateDTO;
import com.cloudinfra.system.service.ISysMenuService;
import com.cloudinfra.system.converter.SysMenuConverter;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "菜单管理")
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final ISysMenuService menuService;
    private final SysMenuConverter menuConverter;

    @Operation(summary = "查询菜单列表")
    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    @GetMapping("/list")
    public List<SysMenuVO> list(SysMenu menu) {
        List<SysMenu> menus = menuService.selectMenuList(menu);
        return menuConverter.entityListToVoList(menus);
    }

    @Operation(summary = "查询菜单树")
    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    @GetMapping("/tree")
    public List<SysMenuVO> tree() {
        List<SysMenu> menus = menuService.selectMenuTree();
        return menuConverter.entityListToVoList(menus);
    }

    @Operation(summary = "查询菜单详细")
    @PreAuthorize("@ss.hasPermi('system:menu:query')")
    @GetMapping("/{menuId}")
    public SysMenuVO getInfo(@PathVariable Long menuId) {
        SysMenu menu = menuService.getById(menuId);
        return menuConverter.entityToVo(menu);
    }

    @Operation(summary = "新增菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:add')")
    @AuditLog(module = "MENU", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysMenuCreateDTO menuDTO) {
        menuService.insertMenu(menuDTO);
        return Result.success();
    }

    @Operation(summary = "修改菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    @AuditLog(module = "MENU", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysMenuUpdateDTO menuDTO) {
        menuService.updateMenu(menuDTO);
        return Result.success();
    }

    @Operation(summary = "删除菜单")
    @PreAuthorize("@ss.hasPermi('system:menu:remove')")
    @AuditLog(module = "MENU", action = "DELETE")
    @DeleteMapping("/{menuId}")
    public Result<Void> remove(@PathVariable Long menuId) {
        menuService.deleteMenuById(menuId);
        return Result.success();
    }
}
