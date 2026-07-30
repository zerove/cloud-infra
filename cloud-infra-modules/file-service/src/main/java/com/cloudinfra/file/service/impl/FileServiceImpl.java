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
        // 1. 存储配额校验
        quotaService.checkQuota(userId, content.size());

        // 2. 物理上传
        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath = strategy.upload(content.fileName(), content.inputStream());
        
        // 3. 创建主元数据
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName(content.fileName());
        metadata.setFilePath(storedPath);
        metadata.setFileType(content.contentType());
        metadata.setFileSize(content.size());
        metadata.setCreateTime(new Date());
        metadata.setUploader("system"); 
        metadata.setCurrentVersion(1);
        metadataMapper.insert(metadata);
        
        // 4. 创建初始版本快照 v1
        FileVersion version = new FileVersion();
        version.setFileId(metadata.getId());
        version.setVersionNumber(1);
        version.setStoragePath(storedPath);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);
        
        // 5. 更新存储用量
        quotaService.updateUsage(userId, content.size());
        
        return mapToVo(metadata);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileMetadataVO update(Long fileId, FileContent content) throws IOException {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File not found");
        }
        
        // 更新操作通常不进行全量配额校验，但可在实际业务中实现增量校验
        
        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath = strategy.upload(content.fileName(), content.inputStream());

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
    public boolean delete(Long fileId) {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            return false;
        }
        
        StorageStrategy strategy = storageFactory.getStrategy();
        boolean deleted = strategy.delete(metadata.getFilePath());
        
        if (deleted) {
            // 注意：生产环境下应通过关联查询获取 uploaderId 进行用量扣减
            // 示例：quotaService.updateUsage(metadata.getUploaderId(), -metadata.getFileSize());
            
            versionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileId, fileId));
            return metadataMapper.deleteById(fileId) > 0;
        }
        
        return false;
    }

    public List<FileMetadataVO> searchFiles(String keyword) {
        List<FileMetadata> results = metadataMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMetadata>()
                .like(FileMetadata::getFileName, keyword)
        );
        return results.stream().map(this::mapToVo).collect(Collectors.toList());
    }

    private FileMetadataVO mapToVo(FileMetadata entity) {
        if (entity == null) return null;
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