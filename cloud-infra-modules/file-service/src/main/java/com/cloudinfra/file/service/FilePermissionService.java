package com.cloudinfra.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.api.system.ResourceMappingApi;
import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.FilePermission;
import com.cloudinfra.file.mapper.FileMetadataMapper;
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
    private final FileMetadataMapper fileMetadataMapper;
    private final ResourceMappingApi resourceMappingApi;
    private final UserApi userApi;

    /**
     * 校验用户是否拥有对文件的特定权限
     * 校验逻辑: 上传者 OWNER -> 拓扑所有权 (用户/部门) -> 精细化权限表 (level 匹配)
     */
    public boolean hasAccess(Long userId, Long fileId, String requiredLevel) {
        // 0. 上传者默认拥有 OWNER 权限
        FileMetadata metadata = fileMetadataMapper.selectById(fileId);
        if (metadata != null && metadata.getUploader() != null
                && metadata.getUploader().equals(String.valueOf(userId))) {
            return true;
        }

        // 1. 拓扑权限校验 (优先级高)
        try {
            Result<Set<Long>> result = resourceMappingApi.getEffectiveNodes(fileId.toString(), "FILE");
            if (result != null && result.getData() != null) {
                Set<Long> effectiveNodes = result.getData();
                
                // 如果用户 ID 在拓扑集合中 -> 直接通过
                if (effectiveNodes.contains(userId)) {
                    return true;
                }
                
                // 获取当前用户所属部门，校验部门是否在拓扑集合中
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

        // 2. 精细化权限校验：匹配主体与权限等级（WRITE 隐含 READ）
        int levelRank = levelRank(requiredLevel);
        LambdaQueryWrapper<FilePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FilePermission::getFileId, fileId)
               .eq(FilePermission::getSubjectType, "USER")
               .eq(FilePermission::getSubjectId, userId.toString())
               .in(FilePermission::getPermissionLevel, permittedLevels(levelRank));
        
        return permissionMapper.selectCount(wrapper) > 0;
    }

    private int levelRank(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "READ" -> 1;
            case "WRITE" -> 2;
            case "OWNER" -> 3;
            default -> 0;
        };
    }

    /**
     * 权限等级集合：请求 READ 时包含 READ/WRITE/OWNER；请求 WRITE 时包含 WRITE/OWNER
     */
    private java.util.List<String> permittedLevels(int rank) {
        java.util.List<String> levels = new java.util.ArrayList<>();
        if (rank >= 1) levels.add("READ");
        if (rank >= 2) levels.add("WRITE");
        if (rank >= 3) levels.add("OWNER");
        return levels;
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