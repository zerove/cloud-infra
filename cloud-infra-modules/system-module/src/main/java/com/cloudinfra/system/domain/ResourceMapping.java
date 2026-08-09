package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 资源拓扑映射实体
 * 定义平台资源（文件、设备等）与组织节点（部门、用户、角色）的绑定关系
 */
@Data
@TableName("sys_resource_mapping")
public class ResourceMapping {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资源唯一标识 (例如: 文件ID, 设备ID)
     */
    private String resourceId;

    /**
     * 资源类型 (例如: "FILE", "DEVICE", "NOTIFICATION")
     */
    private String resourceType;

    /**
     * 绑定节点ID (例如: 部门ID, 用户ID)
     */
    private Long ownerId;

    /**
     * 节点类型 (例如: "DEPT", "USER", "ROLE")
     */
    private String nodeType;

    /**
     * 关系类型 (例如: "OWNER" 所有者, "MANAGER" 管理员, "MEMBER" 成员)
     */
    private String relationType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}