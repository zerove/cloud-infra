package com.cloudinfra.file.service.impl;

import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.FileVersion;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.FileVersionMapper;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.file.service.StorageFactory;
import com.cloudinfra.file.service.StorageStrategy;
import com.cloudinfra.file.service.StorageQuotaService;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements IFileService {

    private final StorageFactory storageFactory;
    private final FileMetadataMapper metadataMapper;
    private final FileVersionMapper versionMapper;
    private final FilePermissionService permissionService;
    private final StorageQuotaService quotaService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileMetadataVO upload(Long userId, FileContent content) throws IOException {
        // 1. 存储配额预检查（友好提示） + 原子预留（真正强校验）
        quotaService.checkQuota(userId, content.size());
        quotaService.tryReserve(userId, content.size());

        // 2. 物理上传
        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath = strategy.upload(content.fileName(), content.inputStream());

        // 3. 注册回滚补偿：若后续 DB 操作失败导致事务回滚，删除已落盘的孤儿文件
        registerFileOnCleanup(strategy, storedPath);

        // 4. 创建主元数据（记录真实上传者）
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName(content.fileName());
        metadata.setFilePath(storedPath);
        metadata.setFileType(content.contentType());
        metadata.setFileSize(content.size());
        metadata.setCreateTime(new Date());
        metadata.setUploader(String.valueOf(userId));
        metadata.setCurrentVersion(1);
        metadataMapper.insert(metadata);
        
        // 5. 创建初始版本快照 v1
        FileVersion version = new FileVersion();
        version.setFileId(metadata.getId());
        version.setVersionNumber(1);
        version.setStoragePath(storedPath);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);
        
        return mapToVo(metadata);
    }

    /**
     * 注册事务回滚补偿：仅当事务最终回滚（ROLLED_BACK）时删除已写入的文件，
     * 避免失败上传留下孤儿文件占用存储。
     */
    private void registerFileOnCleanup(StorageStrategy strategy, String storedPath) {
        if (storedPath == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    boolean removed = strategy.delete(storedPath);
                    if (removed) {
                        log.debug("事务回滚，已清理孤儿文件: {}", storedPath);
                    }
                } catch (Exception e) {
                    log.warn("事务回滚后清理孤儿文件失败: {}", storedPath, e);
                }
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileMetadataVO update(Long fileId, FileContent content, Long userId) throws IOException {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File not found");
        }

        // 写权限校验（含上传者本人或授权用户）
        if (!permissionService.hasAccess(userId, fileId, "WRITE")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to update this file");
        }

        // 增量配额预留：新版本大小 - 旧版本大小（原子）
        long delta = content.size() - (metadata.getFileSize() == null ? 0 : metadata.getFileSize());
        if (delta > 0) {
            quotaService.tryReserve(userId, delta);
        }

        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath = strategy.upload(content.fileName(), content.inputStream());

        // 注册回滚补偿：事务回滚时删除新落盘的版本文件，避免孤儿文件
        registerFileOnCleanup(strategy, storedPath);

        int newVersion = metadata.getCurrentVersion() + 1;
        metadata.setCurrentVersion(newVersion);
        metadata.setFilePath(storedPath);
        metadata.setFileSize(content.size());
        metadataMapper.updateById(metadata);

        FileVersion version = new FileVersion();
        version.setFileId(fileId);
        version.setVersionNumber(newVersion);
        version.setStoragePath(storedPath);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);

        return mapToVo(metadata);
    }

    @Override
    public InputStream download(Long fileId, Long userId) throws IOException {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File not found");
        }

        if (!permissionService.hasAccess(userId, fileId, "READ")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to download this file");
        }

        StorageStrategy strategy = storageFactory.getStrategy();
        return strategy.download(metadata.getFilePath());
    }

    @Override
    public InputStream downloadVersion(Long fileId, Integer version, Long userId) throws IOException {
        if (!permissionService.hasAccess(userId, fileId, "READ")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to download this version");
        }

        FileVersion versionEntity = versionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileId, fileId)
                .eq(FileVersion::getVersionNumber, version)
        );

        if (versionEntity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File version not found");
        }

        StorageStrategy strategy = storageFactory.getStrategy();
        return strategy.download(versionEntity.getStoragePath());
    }

    @Override
    public List<FileVersion> getVersionHistory(Long fileId) {
        return versionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileId, fileId)
                .orderByDesc(FileVersion::getVersionNumber)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long fileId, Long userId) {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            return false;
        }

        // 写权限校验（含上传者本人或授权用户）
        if (!permissionService.hasAccess(userId, fileId, "WRITE")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to delete this file");
        }

        StorageStrategy strategy = storageFactory.getStrategy();
        boolean deleted = strategy.delete(metadata.getFilePath());
        
        if (deleted) {
            // 释放配额：优先使用元数据中的真实上传者，回退到当前操作者
            Long ownerId = parseUploader(metadata.getUploader());
            if (ownerId == null) {
                ownerId = userId;
            }
            if (metadata.getFileSize() != null) {
                quotaService.release(ownerId, metadata.getFileSize());
            }

            versionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileId, fileId));
            return metadataMapper.deleteById(fileId) > 0;
        }
        
        return false;
    }

    /**
     * 解析 uploader 字段为 Long（兼容历史字符串与数字格式）
     */
    private Long parseUploader(String uploader) {
        if (uploader == null || uploader.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(uploader.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<FileMetadataVO> searchFiles(String keyword) {
        List<FileMetadata> results = metadataMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMetadata>()
                .like(FileMetadata::getFileName, keyword)
        );
        return results.stream().map(this::mapToVo).collect(Collectors.toList());
    }

    private FileMetadataVO mapToVo(FileMetadata entity) {
        if (entity == null) {
            return null;
        }
        return new FileMetadataVO(
            entity.getId(),
            entity.getFileName(),
            entity.getFilePath(),
            "/file/download/" + entity.getId(),
            entity.getFileSize(),
            entity.getFileType(),
            entity.getCreateTime() != null 
                ? LocalDateTime.ofInstant(entity.getCreateTime().toInstant(), java.time.ZoneId.systemDefault())
                : null
        );
    }
}