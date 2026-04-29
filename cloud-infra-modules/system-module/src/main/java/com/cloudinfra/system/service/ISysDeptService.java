package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysDept;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    List<SysDept> selectDeptList(Long parentId);
    boolean insertDept(SysDept dept);
    void deleteDeptById(Long deptId);
}
