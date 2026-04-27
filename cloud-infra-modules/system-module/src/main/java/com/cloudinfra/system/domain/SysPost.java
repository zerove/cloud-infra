package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;

@Data
@TableName("sys_post")
public class SysPost implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long postId;
    private String postCode;
    private String postName;
    private Integer postSort;
    private String status;
}
