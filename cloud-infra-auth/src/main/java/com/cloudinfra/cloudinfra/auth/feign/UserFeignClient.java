package com.cloudinfra.cloudinfra.auth.feign;

import com.cloudinfra.common.dto.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "system-module", contextId = "userClient")
public interface UserFeignClient {
    @GetMapping("/users/username/{username}")
    UserDTO getByUsername(@PathVariable("username") String username);
}
