package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.system.domain.SysPost;
import org.apache.ibatis.annotations.Mapper;

/**
 * 岗位 Mapper (M6-01 多租户隔离)
 * 类级 @TenantFilter: 所有 select/insert/update/delete 自动追加 WHERE tenant_id = ?
 */
@Mapper
@TenantFilter
public interface SysPostMapper extends BaseMapper<SysPost> {
}
