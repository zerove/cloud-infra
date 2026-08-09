package com.cloudinfra.system.domain.vo;

import java.util.List;
import java.util.Objects;

/**
 * 角色响应 VO
 */
public class SysRoleVO {
    private Long roleId;
    private String roleName;
    private String roleKey;
    private Integer roleOrder;
    private Integer status;
    private List<Long> permissionIds;

    public SysRoleVO() {}

    public SysRoleVO(Long roleId, String roleName, String roleKey, Integer roleOrder, Integer status, List<Long> permissionIds) {
        this.roleId = roleId;
        this.roleName = roleName;
        this.roleKey = roleKey;
        this.roleOrder = roleOrder;
        this.status = status;
        this.permissionIds = permissionIds;
    }

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

    public List<Long> getPermissionIds() { return permissionIds; }
    public void setPermissionIds(List<Long> permissionIds) { this.permissionIds = permissionIds; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SysRoleVO sysRoleVO = (SysRoleVO) o;
        return Objects.equals(roleId, sysRoleVO.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId);
    }
}
