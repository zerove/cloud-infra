package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysRoleMenu;
import com.cloudinfra.system.mapper.SysRoleMenuMapper;
import com.cloudinfra.system.service.ISysRoleMenuService;
import org.springframework.stereotype.Service;

@Service
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements ISysRoleMenuService {
}
