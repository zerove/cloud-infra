package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    /** 创建并返回数据库生成的主键，避免按名称反查。 */
    Long createDeptReturningId(SysDeptCreateDTO dto);
    List<SysDept> selectDeptList(SysDept dept);
    IPage<SysDept> selectDeptList(Page<SysDept> page, SysDept dept);
    List<SysDept> buildDeptTree(List<SysDept> depts);
    boolean insertDept(SysDeptCreateDTO deptDTO);
    boolean insertDeptWithException(SysDeptCreateDTO deptDTO);
    boolean updateDept(SysDeptUpdateDTO deptDTO);
    boolean deleteDeptById(Long deptId);
}
