package com.cloudinfra.system.domain.vo;

import java.util.List;
import java.util.Objects;

/**
 * 菜单响应 VO
 */
public class SysMenuVO {
    private Long menuId;
    private Long parentId;
    private String menuName;
    private String perms;
    private String path;
    private String component;
    private Integer frameStatus;
    private String menuType;
    private Integer orderNum;
    private Integer visibleStatus;
    private Integer status;
    private List<SysMenuVO> children;

    public SysMenuVO() {}

    public SysMenuVO(Long menuId, Long parentId, String menuName, String perms, String path, 
                     String component, Integer frameStatus, String menuType, Integer orderNum, 
                     Integer visibleStatus, Integer status, List<SysMenuVO> children) {
        this.menuId = menuId;
        this.parentId = parentId;
        this.menuName = menuName;
        this.perms = perms;
        this.path = path;
        this.component = component;
        this.frameStatus = frameStatus;
        this.menuType = menuType;
        this.orderNum = orderNum;
        this.visibleStatus = visibleStatus;
        this.status = status;
        this.children = children;
    }

    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getMenuName() { return menuName; }
    public void setMenuName(String menuName) { this.menuName = menuName; }

    public String getPerms() { return perms; }
    public void setPerms(String perms) { this.perms = perms; }

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

    public List<SysMenuVO> getChildren() { return children; }
    public void setChildren(List<SysMenuVO> children) { this.children = children; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SysMenuVO sysMenuVO = (SysMenuVO) o;
        return Objects.equals(menuId, sysMenuVO.menuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(menuId);
    }
}
