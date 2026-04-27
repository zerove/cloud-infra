package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;

@Data
@TableName("sys_post")
public class SysPost implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId
    private Long postId;
    private String postCode;
    private String postName;
    private Integer postSort;
    private String status;
}
