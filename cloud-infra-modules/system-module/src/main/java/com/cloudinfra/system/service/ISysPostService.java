package com.cloudinfra.system.service;

import java.util.List;

/**
 * 岗位业务接口
 */
public interface ISysPostService {
    /**
     * 更新岗位权限
     * @param postId 岗位ID
     * @param permissionIds 权限ID列表
     */
    void updatePostPermissions(Long postId, List<Long> permissionIds);
}
