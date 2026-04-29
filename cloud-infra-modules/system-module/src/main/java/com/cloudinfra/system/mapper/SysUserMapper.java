package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.system.domain.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("SELECT count(*) FROM sys_user")
    Long countUsers();

    Long countByDeptId(Long deptId);

    @Select("SELECT count(*) FROM sys_user WHERE post_id = #{postId}")
    Long countByPostId(Long postId);
}
