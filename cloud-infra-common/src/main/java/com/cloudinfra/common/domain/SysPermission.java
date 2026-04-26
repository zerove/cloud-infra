package com.cloudinfra.common.domain;

import lombok.Data;

@Data
public class SysPermission {
    private Long id;
    private String name;
    private String perms;
    private String path;
    private Integer deleted;
}
