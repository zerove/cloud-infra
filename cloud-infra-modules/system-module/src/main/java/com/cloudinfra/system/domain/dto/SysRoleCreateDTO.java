package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/**
 * 角色创建请求 DTO
 */
@Data
public class SysRoleCreateDTO {
    @NotBlank(message = "角色名称不能为空")
    @Size(min = 2, max = 20, message = "角色名称长度必须在 2-20 个字符之间")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    @Size(min = 2, max = 20, message = "角色编码长度必须在 2-20 个字符之间")
    private String roleKey;
    private Integer roleOrder;
    private Integer status;
    private List<Long> menuIds;
    private List<Long> deptIds;

    // Manual getters/setters for Lombok compatibility
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public Integer getRoleOrder() { return roleOrder; }
    public void setRoleOrder(Integer roleOrder) { this.roleOrder = roleOrder; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<Long> getMenuIds() { return menuIds; }
    public void setMenuIds(List<Long> menuIds) { this.menuIds = menuIds; }
    public List<Long> getDeptIds() { return deptIds; }
    public void setDeptIds(List<Long> deptIds) { this.deptIds = deptIds; }
}
