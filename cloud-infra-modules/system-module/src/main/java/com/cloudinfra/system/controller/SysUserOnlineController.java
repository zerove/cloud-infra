package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.framework.redis.service.RedisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Tag(name = "在线用户监控")
@RestController
@RequestMapping("/system/online")
@RequiredArgsConstructor
public class SysUserOnlineController {

    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @Operation(summary = "查询在线用户列表")
    @PreAuthorize("hasAuthority('monitor:online:list')")
    @GetMapping("/list")
    public Result<List<LoginUser>> list() {
        java.util.Set<String> keys = redisTemplate.keys("auth:session:*");
        List<LoginUser> userOnlineList = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                LoginUser user = (LoginUser) redisTemplate.opsForValue().get(key);
                if (user != null) {
                    userOnlineList.add(user);
                }
            }
        }
        return Result.success(userOnlineList);
    }

    @Operation(summary = "强退用户")
    @PreAuthorize("hasAuthority('monitor:online:forceLogout')")
    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @DeleteMapping("/{tokenId}")
    public Result<Void> forceLogout(@PathVariable String tokenId) {
        redisTemplate.delete(tokenId);
        return Result.success();
    }
}
