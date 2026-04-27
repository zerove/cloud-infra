package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;

/**
 * 部门对象
 */
@Data
@TableName("sys_dept")
public class SysDept implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId
    private Long deptId;
    private String deptName;
    private Long parentId;
    private String ancestors;
    private Integer orderNum;
    private String status;
}
