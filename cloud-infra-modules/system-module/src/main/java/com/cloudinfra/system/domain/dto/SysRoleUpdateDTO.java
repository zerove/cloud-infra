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

    // Manual getters/setters for Lombok compatibility
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
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
