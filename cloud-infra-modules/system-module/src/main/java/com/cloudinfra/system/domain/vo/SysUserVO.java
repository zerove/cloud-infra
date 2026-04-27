package com.cloudinfra.system.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysUserVO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private Integer status;
    private LocalDateTime createTime;
    private java.util.List<String> permissions;
}
