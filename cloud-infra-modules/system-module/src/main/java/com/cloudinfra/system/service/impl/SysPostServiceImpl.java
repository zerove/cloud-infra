package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.service.ISysPostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 岗位业务实现类
 */
@Service
public class SysPostServiceImpl implements ISysPostService {

    // 假设存在对应的 Mapper，实际项目中需注入
    // @Autowired
    // private SysPostPermissionMapper postPermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePostPermissions(Long postId, List<Long> permissionIds) {
        // 1. 删除岗位原有权限
        // postPermissionMapper.deleteByPostId(postId);
        
        // 2. 插入新权限
        if (permissionIds != null && !permissionIds.isEmpty()) {
            // postPermissionMapper.insertBatch(postId, permissionIds);
        }
    }
}
