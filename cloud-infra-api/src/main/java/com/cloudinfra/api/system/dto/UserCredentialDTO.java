package com.cloudinfra.api.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 用户登录凭据数据传输对象
 *
 * 仅用于 auth 模块内部认证（密码哈希校验），按用户名/ID 定向查询。
 * 与 {@link UserDTO} 不同，本 DTO 携带 password 字段（BCrypt 密文），
 * 因此对应端点必须受内部签名保护，禁止对外暴露。
 *
 * @author cloud-infra
 */
@Schema(description = "用户登录凭据数据传输对象（内部专用，含密码哈希）")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCredentialDTO {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "部门ID")
    private Long deptId;

    @Schema(description = "角色ID")
    private Long roleId;

    @Schema(description = "密码哈希（BCrypt 密文，仅认证链路使用）")
    private String password;

    @Schema(description = "角色标识集合")
    private Set<String> roles;

    @Schema(description = "所属租户 ID（M6-01，多租户隔离）")
    private String tenantId;

    @Schema(description = "权限标识集合")
    private Set<String> permissions;
}