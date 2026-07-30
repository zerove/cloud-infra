package com.cloudinfra.system.domain.vo;

import com.cloudinfra.system.domain.SysDept;
import java.util.ArrayList;
import java.util.List;

public record DeptTreeVO(
    Long id,
    Long parentId,
    String label,
    List<DeptTreeVO> children
) {
    public DeptTreeVO {
        if (children == null) {
            children = new ArrayList<>();
        }
    }

    public static DeptTreeVO fromSysDept(SysDept dept) {
        return new DeptTreeVO(
            dept.getDeptId(),
            dept.getParentId(),
            dept.getDeptName(),
            new ArrayList<>()
        );
    }
}
