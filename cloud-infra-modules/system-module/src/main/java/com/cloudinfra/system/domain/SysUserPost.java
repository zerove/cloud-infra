package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@TableName("sys_user_post")
@AllArgsConstructor
@NoArgsConstructor
public class SysUserPost implements Serializable {
    private Long userId;
    private Long postId;
}
