package com.cloudinfra.file.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.cloudinfra.framework.web.monitor.BusinessMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class FileServiceImpl implements IFileService {


    private final StorageFactory storageFactory;
    private final FileMetadataMapper metadataMapper;
    private final FileVersionMapper versionMapper;
    private final FilePermissionService permissionService;
    private final StorageQuotaService quotaService;
    private final TransactionTemplate transactionTemplate;

    public FileServiceImpl(
        StorageFactory storageFactory,
        FileMetadataMapper metadataMapper,
        FileVersionMapper versionMapper,
        FilePermissionService permissionService,
        StorageQuotaService quotaService,
        org.springframework.transaction.support.TransactionTemplate transactionTemplate
    ) {
        this.storageFactory = storageFactory;
        this.metadataMapper = metadataMapper;
        this.versionMapper = versionMapper;
        this.permissionService = permissionService;
        this.quotaService = quotaService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @BusinessMetric(metric = "file.upload.failures", reason = "upload")
    public FileMetadataVO upload(Long userId, FileContent content) throws IOException {
        // 1. 配额预检查（友好提示）+ 原子预留（短事务：StorageQuotaService 自带 DB 操作，
        //    物理上传前锁定额度，避免慢上传期间持有配额行锁）
        quotaService.checkQuota(userId, content.size());
        quotaService.tryReserve(userId, content.size());

        // 2. 物理上传（数据库事务外执行，避免慢存储长时间占用连接与配额行锁）
        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath;
        try {
            storedPath = strategy.upload(content.fileName(), content.inputStream());
        } catch (IOException | RuntimeException e) {
            // 上传失败：立即释放已预留额度，不留孤儿预留
            quotaService.release(userId, content.size());
            throw e;
        }

        // 3. 元数据落库（程序化短事务，仅两次轻量 INSERT）
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName(content.fileName());
        metadata.setFilePath(storedPath);
        metadata.setFileType(content.contentType());
        metadata.setFileSize(content.size());
        metadata.setCreateTime(new Date());
        metadata.setUploader(String.valueOf(userId));
        metadata.setCurrentVersion(1);
        try {
            transactionTemplate.executeWithoutResult(status ->
                persistMetadataAndVersion(metadata, 1, storedPath));
        } catch (RuntimeException e) {
            // 落库失败：回滚配额预留并删除已落盘的物理文件
            quotaService.release(userId, content.size());
            deleteQuietly(strategy, storedPath);
            throw e;
        }

        return mapToVo(metadata);
    }

    /**
     * 元数据与版本快照在同一短事务内落库。
     * 由 upload 在事务外通过 TransactionTemplate 调用，事务边界最小化。
     */
    private void persistMetadataAndVersion(FileMetadata metadata, int versionNumber, String storedPath) {
        metadataMapper.insert(metadata);
        FileVersion version = new FileVersion();
        version.setFileId(metadata.getId());
        version.setVersionNumber(versionNumber);
        version.setStoragePath(storedPath);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);
    }

    private void deleteQuietly(StorageStrategy strategy, String storedPath) {
        try {
            strategy.delete(storedPath);
        } catch (Exception cleanupError) {
            log.warn("失败上传清理物理文件未成功，等待回收任务处理: {}", storedPath, cleanupError);
        }
    }

    @Override
    public FileMetadataVO update(Long fileId, FileContent content, Long userId) throws IOException {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File not found");
        }

        // 写权限校验（含上传者本人或授权用户）
        if (!permissionService.hasAccess(userId, fileId, "WRITE")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to update this file");
        }

        // 增量配额预留（短事务）：新版本大小 - 旧版本大小
        long delta = content.size() - (metadata.getFileSize() == null ? 0 : metadata.getFileSize());
        if (delta > 0) {
            quotaService.tryReserve(userId, delta);
        }

        // 物理上传（数据库事务外）
        StorageStrategy strategy = storageFactory.getStrategy(content.size(), content.contentType());
        String storedPath;
        try {
            storedPath = strategy.upload(content.fileName(), content.inputStream());
        } catch (IOException | RuntimeException e) {
            if (delta > 0) {
                quotaService.release(userId, delta);
            }
            throw e;
        }

        int newVersion = metadata.getCurrentVersion() + 1;
        metadata.setCurrentVersion(newVersion);
        metadata.setFilePath(storedPath);
        metadata.setFileSize(content.size());

        // 元数据落库（程序化短事务）
        try {
            transactionTemplate.executeWithoutResult(status -> {
                metadataMapper.updateById(metadata);
                FileVersion version = new FileVersion();
                version.setFileId(fileId);
                version.setVersionNumber(newVersion);
                version.setStoragePath(storedPath);
                version.setCreateTime(LocalDateTime.now());
                versionMapper.insert(version);
            });
        } catch (RuntimeException e) {
            // 落库失败：回滚增量配额并删除新版本物理文件
            if (delta > 0) {
                quotaService.release(userId, delta);
            }
            deleteQuietly(strategy, storedPath);
            throw e;
        }

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

        // 按实际存放位置解析, 而非用 getStrategy() 恒定返回 local(2026-10-06 实测缺陷)
        StorageStrategy strategy = storageFactory.resolveForRead(metadata.getFilePath());
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

        // 按实际存放位置解析(同上, 避免大文件版本读不到)
        StorageStrategy strategy = storageFactory.resolveForRead(versionEntity.getStoragePath());
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

        // 删除同样必须命中真实存储后, 否则 MinIO 上的大文件删不掉且会残留配额占用
        StorageStrategy strategy = storageFactory.resolveForRead(metadata.getFilePath());
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

    @Override
    public IPage<FileMetadataVO> searchFiles(String keyword, long pageNum, long pageSize) {
        Page<FileMetadata> page = new Page<>(pageNum, pageSize);
        IPage<FileMetadata> resultPage = metadataMapper.selectPage(page,
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMetadata>()
                .like(FileMetadata::getFileName, keyword)
        );
        return resultPage.convert(this::mapToVo);
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

    @Override
    public String getObjectKeyForDownload(Long fileId, Long userId) {
        FileMetadata metadata = metadataMapper.selectById(fileId);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "File not found");
        }
        if (!permissionService.hasAccess(userId, fileId, "READ")) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "You do not have permission to download this file");
        }
        // 返回 MinIO object key (即 file_path), 不返回 InputStream
        return metadata.getFilePath();
    }
}
