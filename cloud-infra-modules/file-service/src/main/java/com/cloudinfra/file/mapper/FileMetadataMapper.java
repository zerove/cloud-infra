package com.cloudinfra.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.file.domain.FileMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件元数据 Mapper (M6-01 多租户隔离)
 * 类级 @TenantFilter: 所有 CRUD 自动追加 WHERE tenant_id = ?
 */
@Mapper
@TenantFilter
public interface FileMetadataMapper extends BaseMapper<FileMetadata> {
}
