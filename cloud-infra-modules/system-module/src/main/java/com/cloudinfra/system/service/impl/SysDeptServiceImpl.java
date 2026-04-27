package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.mapper.SysDeptMapper;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {
    @Override
    public boolean insertDept(SysDept dept) {
        return this.save(dept);
    }

    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptList(Long parentId) {
        return this.list(new LambdaQueryWrapper<SysDept>()
                .eq(parentId != null, SysDept::getParentId, parentId)
                .orderByAsc(SysDept::getOrderNum));
    }
}
