package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

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

    // Manual getters/setters for Lombok compatibility
    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }
    public String getMenuName() { return menuName; }
    public void setMenuName(String menuName) { this.menuName = menuName; }
    public String getPerms() { return perms; }
    public void setPerms(String perms) { this.perms = perms; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public Integer getFrameStatus() { return frameStatus; }
    public void setFrameStatus(Integer frameStatus) { this.frameStatus = frameStatus; }
    public String getMenuType() { return menuType; }
    public void setMenuType(String menuType) { this.menuType = menuType; }
    public Integer getOrderNum() { return orderNum; }
    public void setOrderNum(Integer orderNum) { this.orderNum = orderNum; }
    public Integer getVisibleStatus() { return visibleStatus; }
    public void setVisibleStatus(Integer visibleStatus) { this.visibleStatus = visibleStatus; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
