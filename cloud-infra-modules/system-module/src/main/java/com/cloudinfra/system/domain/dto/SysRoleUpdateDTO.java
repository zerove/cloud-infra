package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * 角色更新请求 DTO
 */
@Data
public class SysRoleUpdateDTO {
    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    private String roleName;
    private String roleKey;
    private Integer roleOrder;
    private Integer status;
    private List<Long> menuIds;
    private List<Long> deptIds;
}
