package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.converter.SysUserConverter;
import com.cloudinfra.system.domain.dto.SysUserDTO;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.cloudinfra.common.dto.UserDTO;

@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final ISysUserService sysUserService;
    private final SysUserConverter sysUserConverter;

    @GetMapping("/list")
    public Result<PageResult<SysUserVO>> list(SysUserDTO dto) {
        return Result.success(sysUserService.getPageList(dto));
    }

    @GetMapping("/{id}")
    public Result<SysUserVO> getInfo(@PathVariable Long id) {
        return Result.success(sysUserConverter.entityToVo(sysUserService.getById(id)));
    }

    @PostMapping
    public Result<?> add(@Validated @RequestBody SysUserDTO userDto) {
        return Result.success(sysUserService.addUser(userDto));
    }

    @PutMapping
    public Result<?> edit(@RequestBody SysUserDTO userDto) {
        return Result.success(sysUserService.updateById(sysUserConverter.dtoToEntity(userDto)));
    }

    @DeleteMapping("/{id}")
    public Result<?> remove(@PathVariable Long id) {
        return Result.success(sysUserService.removeById(id));
    }

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
