package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_role_menu")
public class SysRoleMenu {
    private Long roleId;
    private Long menuId;
}
