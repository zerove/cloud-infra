package com.cloudinfra.gateway.domain.auth;

import java.util.Map;

/**
 * 认证上下文 - 判定引擎的所有输入原材料
 */
public record AuthContext(
    String token,
    String path,
    String method,
    String remoteAddr,
    Map<String, String> headers
) {}
