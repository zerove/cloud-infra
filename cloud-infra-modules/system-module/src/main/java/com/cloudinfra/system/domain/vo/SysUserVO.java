package com.cloudinfra.system.domain.vo;

import java.time.LocalDateTime;

public record SysUserVO(
    Long id,
    String username,
    String email,
    String phone,
    Integer status,
    LocalDateTime createTime,
    java.util.List<String> permissions
) {}
