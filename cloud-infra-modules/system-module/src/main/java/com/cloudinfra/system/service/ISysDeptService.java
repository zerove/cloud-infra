package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysDept;
import java.util.List;

public interface ISysDeptService {
    List<SysDept> selectDeptList(SysDept dept);
    SysDept selectDeptById(Long deptId);
    int insertDept(SysDept dept);
    int updateDept(SysDept dept);
    int deleteDeptById(Long deptId);
}
