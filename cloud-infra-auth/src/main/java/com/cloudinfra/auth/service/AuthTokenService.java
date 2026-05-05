package com.cloudinfra.auth.service;

import com.cloudinfra.common.core.domain.model.LoginUser;
import java.util.Map;

public interface AuthTokenService {
    Map<String, Object> createToken(LoginUser loginUser);
    Map<String, Object> refreshToken(String refreshToken);
    void delToken(String token);
}
