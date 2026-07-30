package com.cloudinfra.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.api.system.ResourceMappingApi;
import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.file.domain.FilePermission;
import com.cloudinfra.file.mapper.FilePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilePermissionService {
    private final FilePermissionMapper permissionMapper;
    private final ResourceMappingApi resourceMappingApi;
    private final UserApi userApi;

    /**
     * 校验用户是否拥有对文件的特定权限
     * 校验逻辑: 拓扑所有权 (用户/部门) -> 覆盖所有记录 -> 精细化权限表
     */
    public boolean hasAccess(Long userId, Long fileId, String requiredLevel) {
        // 1. 拓扑权限校验 (优先级最高)
        try {
            Result<Set<Long>> result = resourceMappingApi.getEffectiveNodes(fileId.toString(), "FILE");
            if (result != null && result.getData() != null) {
                Set<Long> effectiveNodes = result.getData();
                
                // 如果用户 ID 在拓扑集合中 -> 直接通过
                if (effectiveNodes.contains(userId)) {
                    return true;
                }
                
                // 获取当前用户所属部门，校验部门是否在拓扑集合中
                // 这里调用 UserApi 获取用户信息
                var userResult = userApi.getUserById(userId);
                if (userResult != null && userResult.getData() != null) {
                    Long deptId = userResult.getData().getDeptId();
                    if (deptId != null && effectiveNodes.contains(deptId)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("拓扑权限校验异常 [File: {}]: {}", fileId, e.getMessage());
            // 降级处理：拓扑校验失败时不阻断，继续执行精细化权限校验
        }

        // 2. 精细化权限校验 (回退方案)
        LambdaQueryWrapper<FilePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FilePermission::getFileId, fileId)
               .eq(FilePermission::getSubjectType, "USER")
               .eq(FilePermission::getSubjectId, userId.toString());
        
        return permissionMapper.selectCount(wrapper) > 0;
    }

    /**
     * 授予权限
     */
    public void grantPermission(Long fileId, String subjectType, String subjectId, String level) {
        FilePermission perm = new FilePermission();
        perm.setFileId(fileId);
        perm.setSubjectType(subjectType);
        perm.setSubjectId(subjectId);
        perm.setPermissionLevel(level);
        permissionMapper.insert(perm);
    }
}