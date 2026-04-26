package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.ISysRoleService;
import org.springframework.stereotype.Service;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {
}
