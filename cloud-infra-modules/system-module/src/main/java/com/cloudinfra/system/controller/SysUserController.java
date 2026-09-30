package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.system.converter.SysUserConverter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final ISysUserService userService;
    private final ISysPermissionService permissionService;
    private final SysUserConverter userConverter;

    @Operation(summary = "查询用户列表")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public IPage<SysUserVO> list(SysUser user, 
                                 @RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        IPage<SysUser> users = userService.selectUserList(page, user);
        
        // 批量预加载权限，避免 N+1
        List<Long> userIds = users.getRecords().stream()
            .map(SysUser::getUserId)
            .collect(Collectors.toList());
        Map<Long, List<String>> userPermissions = permissionService.getPermissionsByUserIds(userIds);
        
        List<SysUserVO> voList = userConverter.entityListToVoListWithPermissions(users.getRecords(), userPermissions);
        IPage<SysUserVO> voPage = new Page<>(users.getCurrent(), users.getSize(), users.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    @Operation(summary = "新增用户")
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    @AuditLog(module = "USER", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysUserCreateDTO userDTO) {
        userService.insertUser(userDTO);
        return Result.success();
    }

    @Operation(summary = "修改用户")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @AuditLog(module = "USER", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysUserUpdateDTO userDTO) {
        userService.updateUser(userDTO);
        return Result.success();
    }
}
