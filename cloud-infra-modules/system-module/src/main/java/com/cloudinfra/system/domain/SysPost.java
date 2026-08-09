package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_post")
public class SysPost extends BaseEntity {
    @TableId
    private Long postId;
    private String postCode;
    private String postName;
    private Integer postSort;
    private String status;
    private String remark;
}
