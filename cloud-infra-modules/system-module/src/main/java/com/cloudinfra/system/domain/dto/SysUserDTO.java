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
    
    private String phoneNumber;
    
    private Integer status;

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
