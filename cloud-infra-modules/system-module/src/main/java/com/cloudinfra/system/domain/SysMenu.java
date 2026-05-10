package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
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

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private List<SysMenu> children = new ArrayList<>();
}
