package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysDept;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    List<SysDept> selectDeptList(SysDept dept);
    List<SysDept> buildDeptTree(List<SysDept> depts);
    boolean insertDept(SysDept dept);
    boolean insertDeptWithException(SysDept dept);
    boolean updateDept(SysDept dept);
    boolean deleteDeptById(Long deptId);
}
