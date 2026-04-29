package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {

    @Autowired
    private SysUserMapper userMapper;

    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptList(Long parentId) {
        List<SysDept> deptList = this.list(new LambdaQueryWrapper<SysDept>()
                .orderByAsc(SysDept::getOrderNum));
        return buildTree(deptList, parentId == null ? 0L : parentId);
    }

    @Override
    public boolean insertDept(SysDept dept) {
        String[] ancestors = dept.getAncestors().split(",");
        if (ancestors.length >= 5) {
            throw new GlobalException(SystemExceptionCode.DEPT_LEVEL_EXCEED);
        }
        return this.save(dept);
    }

    @Override
    public void deleteDeptById(Long deptId) {
        if (baseMapper.selectCountByParentId(deptId) > 0) {
            throw new GlobalException(SystemExceptionCode.DEPT_HAS_CHILD);
        }
        if (userMapper.countByDeptId(deptId) > 0) {
            throw new GlobalException(SystemExceptionCode.DEPT_IN_USE);
        }
        this.removeById(deptId);
    }

    private List<SysDept> buildTree(List<SysDept> depts, Long parentId) {
        List<SysDept> tree = depts.stream()
                .filter(dept -> dept.getParentId().equals(parentId))
                .collect(Collectors.toList());
        for (SysDept dept : tree) {
            dept.setChildren(buildTree(depts, dept.getDeptId()));
        }
        return tree;
    }
}
