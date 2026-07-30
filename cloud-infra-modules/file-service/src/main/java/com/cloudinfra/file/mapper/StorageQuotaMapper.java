package com.cloudinfra.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.file.domain.StorageQuota;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StorageQuotaMapper extends BaseMapper<StorageQuota> {
}