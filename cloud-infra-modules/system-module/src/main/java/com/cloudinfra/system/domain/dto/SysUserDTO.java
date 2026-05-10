package com.cloudinfra.system.domain.dto;

import com.cloudinfra.common.core.domain.PageDTO;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SysUserDTO extends PageDTO {
    
    private Long id;
    
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    // 仅在新增时需要密码校验，这里简化处理，实际可通过分组校验实现
    private String password;
    
    @Email(message = "邮箱格式不正确")
    private String email;
    
    private String phone;
    
    private Integer status;
}
