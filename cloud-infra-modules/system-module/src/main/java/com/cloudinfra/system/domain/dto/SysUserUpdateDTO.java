package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户更新请求 DTO
 */
@Data
public class SysUserUpdateDTO {
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    private String nickName;
    private Long deptId;
    private Long roleId;
    private Long postId;
    private String email;
    private String phoneNumber;
    /** 帐号状态（0正常 1停用），停用将立即吊销该用户全部会话 */
    private Integer status;
}
