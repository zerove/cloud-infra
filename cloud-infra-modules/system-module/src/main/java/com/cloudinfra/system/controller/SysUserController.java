package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.converter.SysUserConverter;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.cloudinfra.common.dto.UserDTO;

@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final ISysUserService sysUserService;
    private final SysUserConverter sysUserConverter;

    @PreAuthorize("@ss.hasPermission('system:user:list')")
    @GetMapping("/list")
    public Result<PageResult<SysUserVO>> list(SysUserDTO dto) {
        return Result.success(sysUserService.getPageList(dto));
    }

    @PreAuthorize("@ss.hasPermission('system:user:query')")
    @GetMapping("/{id}")
    public Result<SysUserVO> getInfo(@PathVariable Long id) {
        return Result.success(sysUserConverter.entityToVo(sysUserService.getById(id)));
    }

    @PreAuthorize("@ss.hasPermission('system:user:add')")
    @PostMapping
    public Result<Void> add(@Validated @RequestBody SysUserDTO userDto) {
        sysUserService.addUser(userDto);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:user:edit')")
    @PutMapping
    public Result<Void> edit(@RequestBody SysUserDTO userDto) {
        sysUserService.updateById(sysUserConverter.dtoToEntity(userDto));
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('system:user:remove')")
    public Result<Void> remove(@PathVariable Long id) {
        sysUserService.removeById(id);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:user:view')")
    @GetMapping("/users/username/{username}")
    public Result<UserDTO> getByUsername(@PathVariable String username) {
        com.cloudinfra.system.domain.SysUser user = sysUserService.getByUsername(username);
        if (user == null) {
            throw new com.cloudinfra.system.exception.SystemBusinessException(com.cloudinfra.system.exception.SystemExceptionCode.USER_NOT_FOUND);
        }
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setPassword(user.getPassword());
        return Result.success(dto);
    }
}
