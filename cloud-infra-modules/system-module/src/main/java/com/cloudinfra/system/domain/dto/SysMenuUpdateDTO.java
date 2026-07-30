package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * 菜单更新请求 DTO
 */
@Data
public class SysMenuUpdateDTO {
    @NotNull(message = "菜单ID不能为空")
    private Long menuId;

    private String menuName;
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
