package com.cloudinfra.api.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册用户请求 DTO
 * 仅用于注册场景，明文密码仅存在于请求方向，不进入查询响应。
 */
@Schema(description = "注册用户请求")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterUserDTO {

    @Schema(description = "用户名", example = "alice")
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须在 3-20 个字符之间")
    private String username;

    @Schema(description = "昵称", example = "Alice")
    private String nickName;

    @Schema(description = "明文密码（仅请求方向，服务端 BCrypt 加密存储）")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在 6-20 个字符之间")
    private String password;

    @Schema(description = "部门ID")
    private Long deptId;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "岗位ID")
    private Long postId;

    @Schema(description = "邮箱", example = "alice@example.com")
    private String email;

    @Schema(description = "手机号", example = "13800000000")
    private String phoneNumber;
}