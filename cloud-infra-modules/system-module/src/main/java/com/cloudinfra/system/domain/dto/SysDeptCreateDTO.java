package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门创建请求 DTO
 */
@Data
public class SysDeptCreateDTO {
    @NotBlank(message = "部门名称不能为空")
    @Size(min = 2, max = 20, message = "部门名称长度必须在 2-20 个字符之间")
    private String deptName;

    private Long parentId;
    private Integer orderNum;
    private String leader;
    private String phone;
    private String email;
    private String status;
}
