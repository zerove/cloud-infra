package com.cloudinfra.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.common.core.annotation.DataScope;
import com.cloudinfra.system.domain.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @DataScope(deptAlias = "u", userAlias = "u")
    IPage<SysUser> selectUserPage(IPage<SysUser> page, @Param("user") SysUser user);
    int countByDeptId(@Param("deptId") Long deptId);
    int countByPostId(@Param("postId") Long postId);
}
