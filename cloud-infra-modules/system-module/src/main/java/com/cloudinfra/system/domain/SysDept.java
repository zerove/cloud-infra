package com.cloudinfra.system.domain;

import java.io.Serializable;

/**
 * 部门对象
 */
public class SysDept implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long deptId;
    private String deptName;
    private Long parentId;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
}
