package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单创建请求 DTO
 */
@Data
public class SysMenuCreateDTO {
    @NotBlank(message = "菜单名称不能为空")
    @Size(min = 2, max = 20, message = "菜单名称长度必须在 2-20 个字符之间")
    private String menuName;

    @NotBlank(message = "菜单权限标识不能为空")
    @Size(min = 2, max = 50, message = "权限标识长度必须在 2-50 个字符之间")
    private String perms;

    private Long parentId;
    private String path;
    private String component;
    private Integer frameStatus;
    private String menuType;
    private Integer orderNum;
    private Integer visibleStatus;
    private Integer status;
}
