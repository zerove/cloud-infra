package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.io.Serializable;

@Data
@TableName("sys_post")
@EqualsAndHashCode(callSuper = false)
public class SysPost implements Serializable {
    @TableId
    private Long postId;
    private String postCode;
    private String postName;
    private Integer postSort;
    private String status; // 0:正常, 1:停用
    private String remark;
}
