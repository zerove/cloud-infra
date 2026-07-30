package com.cloudinfra.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * 部门更新请求 DTO
 */
@Data
public class SysDeptUpdateDTO {
    @NotNull(message = "部门ID不能为空")
    private Long deptId;

    private String deptName;
    private Long parentId;
    private Integer orderNum;
    private String leader;
    private String phone;
    private String email;
    private String status;
}
