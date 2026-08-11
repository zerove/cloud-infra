package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.domain.dto.SysDeptCreateDTO;
import com.cloudinfra.system.domain.dto.SysDeptUpdateDTO;
import com.cloudinfra.system.converter.SysDeptConverter;
import com.cloudinfra.system.exception.SystemBusinessException;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {

    private final SysDeptConverter deptConverter;

    @Override
    public List<SysDept> selectDeptList(SysDept dept) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        if (dept.getDeptName() != null) {
            wrapper.like(SysDept::getDeptName, dept.getDeptName());
        }
        if (dept.getParentId() != null) {
            wrapper.eq(SysDept::getParentId, dept.getParentId());
        }
        if (dept.getStatus() != null) {
            wrapper.eq(SysDept::getStatus, dept.getStatus());
        }
        wrapper.orderByAsc(SysDept::getParentId, SysDept::getOrderNum);
        return list(wrapper);
    }

    @Override
    public IPage<SysDept> selectDeptList(Page<SysDept> page, SysDept dept) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        if (dept.getDeptName() != null) {
            wrapper.like(SysDept::getDeptName, dept.getDeptName());
        }
        if (dept.getParentId() != null) {
            wrapper.eq(SysDept::getParentId, dept.getParentId());
        }
        if (dept.getStatus() != null) {
            wrapper.eq(SysDept::getStatus, dept.getStatus());
        }
        wrapper.orderByAsc(SysDept::getParentId, SysDept::getOrderNum);
        return page(page, wrapper);
    }

    @Override
    public List<SysDept> buildDeptTree(List<SysDept> depts) {
        List<SysDept> treeDepts = baseMapper.selectDeptTree();
        return buildTree(treeDepts);
    }

    private List<SysDept> buildTree(List<SysDept> depts) {
        List<SysDept> returnList = new ArrayList<>();
        for (SysDept dept : depts) {
            if (dept.getParentId() == 0) {
                recursionFn(depts, dept);
                returnList.add(dept);
            }
        }
        return returnList.isEmpty() ? depts : returnList;
    }

    private void recursionFn(List<SysDept> list, SysDept t) {
        List<SysDept> children = getChildList(list, t);
        t.setChildren(children);
        for (SysDept child : children) {
            recursionFn(list, child);
        }
    }

    private List<SysDept> getChildList(List<SysDept> list, SysDept t) {
        return list.stream()
                .filter(n -> n.getParentId().equals(t.getDeptId()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean insertDept(SysDeptCreateDTO deptDTO) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getDeptName, deptDTO.getDeptName())
               .eq(SysDept::getParentId, deptDTO.getParentId())
               .eq(SysDept::getDelFlag, "0");
        if (this.getOne(wrapper) != null) {
            throw new SystemBusinessException("同级部门已存在名称为 [" + deptDTO.getDeptName() + "] 的部门");
        }

        SysDept dept = deptConverter.createDtoToEntity(deptDTO);
        SysDept info = this.getById(dept.getParentId());
        if (info != null) {
            dept.setAncestors(info.getAncestors() + "," + dept.getParentId());
        } else {
            dept.setAncestors("0");
        }
        return save(dept);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertDeptWithException(SysDeptCreateDTO deptDTO) {
        insertDept(deptDTO);
        throw new RuntimeException("Test exception for rollback");
    }

    @Override
    public boolean updateDept(SysDeptUpdateDTO deptDTO) {
        SysDept dept = deptConverter.updateDtoToEntity(deptDTO);
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

    private void updateDeptChildren(Long deptId, String newAncestors, String oldAncestors) {
        // Simplified recursive update
    }

    @Override
    public boolean deleteDeptById(Long deptId) {
        return removeById(deptId);
    }
}
