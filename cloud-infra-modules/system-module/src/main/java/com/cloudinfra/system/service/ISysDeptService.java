package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.vo.DeptTreeVO;
import java.util.List;

public interface ISysDeptService extends IService<SysDept> {
    List<SysDept> selectDeptList(Long parentId);
    List<DeptTreeVO> selectDeptTreeList();
    boolean insertDept(SysDept dept);
    boolean deleteDeptById(Long deptId);
}
