package com.cloudinfra.system.domain.vo;

import com.cloudinfra.system.domain.SysDept;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class DeptTreeVO {
    private Long id;
    private Long parentId;
    private String label;
    private List<DeptTreeVO> children = new ArrayList<>();

    public static DeptTreeVO fromSysDept(SysDept dept) {
        DeptTreeVO vo = new DeptTreeVO();
        vo.setId(dept.getDeptId());
        vo.setParentId(dept.getParentId());
        vo.setLabel(dept.getDeptName());
        return vo;
    }
}
