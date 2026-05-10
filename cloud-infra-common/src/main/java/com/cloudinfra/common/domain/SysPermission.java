package com.cloudinfra.common.domain;

import lombok.Data;
import java.io.Serializable;

@Data
public class SysPermission implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String name;
    private String perms;
    private String path;
    private Integer deleted;
}
