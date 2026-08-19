package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "在线用户监控")
@RestController
@RequestMapping("/system/online")
@RequiredArgsConstructor
public class SysUserOnlineController {

    private final org.springframework.data.redis.core.RedisTemplate<
        String,
        Object
    > redisTemplate;

    @Operation(summary = "查询在线用户列表")
    @PreAuthorize("hasAuthority('monitor:online:list')")
    @GetMapping("/list")
    public List<LoginUser> list() {
        String pattern = "auth:session:*";
        java.util.Set<String> keys = new java.util.HashSet<>();
        try (var cursor = redisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        List<LoginUser> userOnlineList = new ArrayList<>();
        for (String key : keys) {
            LoginUser user = (LoginUser) redisTemplate
                .opsForValue()
                .get(key);
            if (user != null) {
                userOnlineList.add(user);
            }
        }
        return userOnlineList;
    }

    @Operation(summary = "强退用户")
    @PreAuthorize("hasAuthority('monitor:online:forceLogout')")
    @AuditLog(module = "ONLINE_USER", action = "FORCE_LOGOUT")
    @DeleteMapping("/{tokenId}")
    public Result<Void> forceLogout(@PathVariable String tokenId) {
        redisTemplate.delete(tokenId);
        return Result.success();
    }
}