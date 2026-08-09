package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.cloudinfra.common.core.domain.BaseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String menuName;

    private Long parentId;

    private Integer orderNum;

    private String path;

    private String component;

    private String menuType;

    private String perms;

    private Integer status;

    private Integer frameStatus;

    private Integer visibleStatus;

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private List<SysMenu> children = new ArrayList<>();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMenuName() { return menuName; }
    public void setMenuName(String menuName) { this.menuName = menuName; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Integer getOrderNum() { return orderNum; }
    public void setOrderNum(Integer orderNum) { this.orderNum = orderNum; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }

    public String getMenuType() { return menuType; }
    public void setMenuType(String menuType) { this.menuType = menuType; }

    public String getPerms() { return perms; }
    public void setPerms(String perms) { this.perms = perms; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getFrameStatus() { return frameStatus; }
    public void setFrameStatus(Integer frameStatus) { this.frameStatus = frameStatus; }

    public Integer getVisibleStatus() { return visibleStatus; }
    public void setVisibleStatus(Integer visibleStatus) { this.visibleStatus = visibleStatus; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public List<SysMenu> getChildren() { return children; }
    public void setChildren(List<SysMenu> children) { this.children = children; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SysMenu sysMenu = (SysMenu) o;
        return Objects.equals(id, sysMenu.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
