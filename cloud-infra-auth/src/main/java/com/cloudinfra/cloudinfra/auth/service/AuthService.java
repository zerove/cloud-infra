package com.cloudinfra.cloudinfra.auth.service;

import com.cloudinfra.cloudinfra.auth.dto.*;
import com.cloudinfra.cloudinfra.auth.feign.UserFeignClient;
import com.cloudinfra.framework.security.utils.JwtUtils;
import com.cloudinfra.common.dto.UserDTO;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

@Service
public class AuthService {
    @Resource private UserFeignClient userFeignClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthResponse login(String username, String password) {
        UserDTO user = userFeignClient.getByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        String token = JwtUtils.generateToken(username);
        return new AuthResponse(token, new UserInfo(user.getId(), user.getUsername()));
    }
}
