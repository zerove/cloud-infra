package com.cloudinfra.auth.feign;

import com.cloudinfra.auth.domain.LoginUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "system-module", contextId = "userFeignClient")
public interface UserFeignClient {
    @GetMapping("/system/user/{userId}")
    LoginUser getUserById(@PathVariable("userId") Long userId);
}
