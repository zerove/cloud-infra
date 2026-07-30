package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.ResourceMapping;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMappingMapper extends BaseMapper<ResourceMapping> {
}