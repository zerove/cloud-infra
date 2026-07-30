package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    List<SysDept> selectDeptList(SysDept dept);
    IPage<SysDept> selectDeptList(Page<SysDept> page, SysDept dept);
    List<SysDept> buildDeptTree(List<SysDept> depts);
    boolean insertDept(SysDeptCreateDTO deptDTO);
    boolean insertDeptWithException(SysDeptCreateDTO deptDTO);
    boolean updateDept(SysDeptUpdateDTO deptDTO);
    boolean deleteDeptById(Long deptId);
}
