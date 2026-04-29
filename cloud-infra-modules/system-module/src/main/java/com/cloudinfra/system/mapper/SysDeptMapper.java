package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.SysDept;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门数据层
 */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {
    Long selectCountByParentId(Long parentId);
}
