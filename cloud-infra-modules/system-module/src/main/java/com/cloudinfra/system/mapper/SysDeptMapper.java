package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.system.domain.SysDept;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@TenantFilter
public interface SysDeptMapper extends BaseMapper<SysDept> {
    java.util.List<SysDept> selectDeptTree();
}
