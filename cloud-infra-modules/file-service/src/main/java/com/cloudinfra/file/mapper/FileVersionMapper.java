package com.cloudinfra.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.file.domain.FileVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileVersionMapper extends BaseMapper<FileVersion> {
}