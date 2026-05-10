package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {

    @Override
    public List<SysDept> selectDeptList(SysDept dept) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getDelFlag, "0");
        if (dept != null) {
            if (dept.getDeptName() != null) {
                wrapper.like(SysDept::getDeptName, dept.getDeptName());
            }
            if (dept.getStatus() != null) {
                wrapper.eq(SysDept::getStatus, dept.getStatus());
            }
        }
        wrapper.orderByAsc(SysDept::getParentId, SysDept::getOrderNum);
        return list(wrapper);
    }

    @Override
    public List<SysDept> buildDeptTree(List<SysDept> depts) {
        List<SysDept> returnList = new ArrayList<>();
        List<Long> tempList = depts.stream().map(SysDept::getDeptId).collect(Collectors.toList());
        for (SysDept dept : depts) {
            if (!tempList.contains(dept.getParentId())) {
                recursionFn(depts, dept);
                returnList.add(dept);
            }
        }
        if (returnList.isEmpty()) {
            returnList = depts;
        }
        return returnList;
    }

    @Override
    public boolean insertDept(SysDept dept) {
        SysDept info = this.getById(dept.getParentId());
        if (info != null) {
            dept.setAncestors(info.getAncestors() + "," + dept.getParentId());
        } else {
            dept.setAncestors("0");
        }
        return save(dept);
    }

    @Override
    public boolean updateDept(SysDept dept) {
        SysDept newParentDept = this.getById(dept.getParentId());
        SysDept oldDept = this.getById(dept.getDeptId());
        if (newParentDept != null && oldDept != null) {
            String newAncestors = newParentDept.getAncestors() + "," + newParentDept.getDeptId();
            String oldAncestors = oldDept.getAncestors();
            dept.setAncestors(newAncestors);
            updateDeptChildren(dept.getDeptId(), newAncestors, oldAncestors);
        }
        return updateById(dept);
    }

    @Override
    public boolean deleteDeptById(Long deptId) {
        return removeById(deptId);
    }

    private void updateDeptChildren(Long deptId, String newAncestors, String oldAncestors) {
        List<SysDept> children = list(new LambdaQueryWrapper<SysDept>().apply("ancestors like {0}", oldAncestors + "," + deptId + "%"));
        for (SysDept child : children) {
            child.setAncestors(child.getAncestors().replaceFirst(oldAncestors, newAncestors));
        }
        if (!children.isEmpty()) {
            updateBatchById(children);
        }
    }

    private void recursionFn(List<SysDept> list, SysDept t) {
        List<SysDept> childList = getChildList(list, t);
        // UI logic usually handles children
    }

    private List<SysDept> getChildList(List<SysDept> list, SysDept t) {
        return list.stream()
                .filter(n -> n.getParentId().equals(t.getDeptId()))
                .collect(Collectors.toList());
    }
}
