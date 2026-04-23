package com.cloudinfra.auth.service;

import com.cloudinfra.auth.domain.LoginUser;
import java.util.Map;

/**
 * 核心令牌服务：负责 JWT 的签发、验证与刷新
 */
public interface AuthTokenService {

    /**
     * 创建令牌 (登录成功后调用)
     */
    Map<String, Object> createToken(LoginUser loginUser);

    /**
     * 刷新令牌
     */
    Map<String, Object> refreshToken(String refreshToken);

    /**
     * 删除令牌 (注销)
     */
    void delToken(String token);
}
