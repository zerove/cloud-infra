package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SysDeptServiceImpl implements ISysDeptService {

    @Override
    public List<SysDept> selectDeptList(SysDept dept) {
        return null;
    }

    @Override
    public SysDept selectDeptById(Long deptId) {
        return null;
    }

    @Override
    public int insertDept(SysDept dept) {
        return 0;
    }

    @Override
    public int updateDept(SysDept dept) {
        return 0;
    }

    @Override
    public int deleteDeptById(Long deptId) {
        return 0;
    }
}
