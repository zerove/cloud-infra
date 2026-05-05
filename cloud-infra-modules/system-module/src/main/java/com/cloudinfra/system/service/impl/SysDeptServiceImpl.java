package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.stereotype.Service;
import com.cloudinfra.system.domain.vo.DeptTreeVO;
import java.util.ArrayList;
import java.util.List;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {

    @Override
    public List<SysDept> selectDeptList(Long parentId) {
        return baseMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .eq(parentId != null, SysDept::getParentId, parentId)
                .orderByAsc(SysDept::getOrderNum));
    }

    @Override
    public List<DeptTreeVO> selectDeptTreeList() {
        List<SysDept> depts = baseMapper.selectList(null);
        List<DeptTreeVO> vos = depts.stream().map(DeptTreeVO::fromSysDept).toList();
        return buildTree(vos, 0L);
    }

    private List<DeptTreeVO> buildTree(List<DeptTreeVO> vos, Long parentId) {
        List<DeptTreeVO> tree = new ArrayList<>();
        for (DeptTreeVO vo : vos) {
            if (parentId.equals(vo.getParentId())) {
                vo.setChildren(buildTree(vos, vo.getId()));
                tree.add(vo);
            }
        }
        return tree;
    }

    @Override
    public boolean insertDept(SysDept dept) {
        return this.save(dept);
    }

    @Override
    public boolean deleteDeptById(Long deptId) {
        return this.removeById(deptId);
    }
}
