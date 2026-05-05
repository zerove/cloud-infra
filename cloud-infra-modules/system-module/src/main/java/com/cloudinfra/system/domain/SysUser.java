package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
// import com.cloudinfra.framework.mybatis.annotation.Encrypted;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId
    private Long userId;
    private String username;
    
    // @Encrypted
    private String phone;
    
    private Long deptId;
    private Integer status;
    private String password;
    private String email;
    private Date createTime;

    // 扩展字段：关联岗位
    private List<Long> postIds;

    // Helper methods for migration
    public void setId(Long id) { this.userId = id; }
    public Long getId() { return this.userId; }
}
