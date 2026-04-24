package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.service.SysPermissionService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.List;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.exception.NacosException;
import cn.hutool.json.JSONUtil;
import com.cloudinfra.system.domain.SysPermission;

@Service
public class SysPermissionServiceImpl implements SysPermissionService {
    @Resource
    private SysPermissionMapper sysPermissionMapper;

    @Resource
    private NacosConfigManager nacosConfigManager;

    @Override
    public List<String> getPermsByUserId(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }

    public void syncPermissionsToNacos() {
        List<SysPermission> all = sysPermissionMapper.selectList(null);
        String json = JSONUtil.toJsonStr(all);
        try {
            nacosConfigManager.getConfigService().publishConfig("gateway-permissions.json", "DEFAULT_GROUP", json);
        } catch (NacosException e) {
            throw new RuntimeException("Sync failed", e);
        }
    }
}
