package com.cloudinfra.system.domain.vo;

import java.util.List;

/**
 * 部门响应 VO
 */
public record SysDeptVO(
    Long deptId,
    String parentId,
    String deptName,
    String leader,
    String phone,
    String email,
    String status,
    String orderNum,
    List<SysDeptVO> children // 支持树形结构
) {}
