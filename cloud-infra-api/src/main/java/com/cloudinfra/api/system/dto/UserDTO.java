package com.cloudinfra.api.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

/**
 * 用户信息数据传输对象
 *
 * @author cloud-infra
 */
@Schema(description = "用户信息数据传输对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    /**
     * 用户ID
     */
    @Schema(description = "用户ID", example = "1")
    private Long userId;

    /**
     * 用户名
     */
    @Schema(description = "用户名", example = "admin")
    private String username;

    /**
     * 部门ID
     */
    @Schema(description = "部门ID", example = "100")
    private Long deptId;

    /**
     * 角色列表
     */
    @Schema(description = "角色标识集合")
    private Set<String> roles;

    /**
     * 权限列表
     */
    @Schema(description = "权限标识集合")
    private Set<String> permissions;

    /**
     * 密码
     */
    @Schema(description = "密码")
    private String password;
}
