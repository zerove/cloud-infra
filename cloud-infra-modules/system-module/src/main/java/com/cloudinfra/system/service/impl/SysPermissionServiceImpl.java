package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.service.SysPermissionService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.List;

@Service
public class SysPermissionServiceImpl implements SysPermissionService {
    @Resource
    private SysPermissionMapper sysPermissionMapper;

    @Override
    public List<String> getPermsByUserId(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }
}
