package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.system.domain.SysMenu;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@TenantFilter
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
