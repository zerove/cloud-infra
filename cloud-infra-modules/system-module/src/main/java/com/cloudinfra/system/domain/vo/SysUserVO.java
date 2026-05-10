package com.cloudinfra.system.domain.vo;

import lombok.Data;

import java.util.Date;

@Data
public class SysUserVO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private Integer status;
    private Date createTime;
    private java.util.List<String> permissions;
}
