package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.Data;

@Data
@TableName("sys_post_permission")
public class SysPostPermission {
    @TableId
    private Long postId;
    private Long permissionId;
}
