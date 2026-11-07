package com.cloudinfra.file.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface IFileService {
    FileMetadataVO upload(Long userId, FileContent content) throws IOException;

    /**
     * 更新现有文件并创建新版本
     */
    FileMetadataVO update(Long fileId, FileContent content, Long userId) throws IOException;

    InputStream download(Long fileId, Long userId) throws IOException;

    /**
     * 下载指定版本的文件
     */
    InputStream downloadVersion(Long fileId, Integer version, Long userId) throws IOException;

    /**
     * 获取文件版本历史
     */
    List<FileVersion> getVersionHistory(Long fileId, int page, int size);

    /**
     * 根据关键词搜索文件
     */
    IPage<FileMetadataVO> searchFiles(String keyword, long pageNum, long pageSize);

    /**
     * 获取文件元数据（含权限校验，READ 语义）
     */
    FileMetadataVO getMetadata(Long fileId, Long userId);

    boolean delete(Long fileId, Long userId);

    /**
     * 获取文件下载用的 objectKey (供 presigned download URL 使用).
     * 内部包含权限校验 (复用 download 路径), 只返回 objectKey 不返回文件流.
     */
    String getObjectKeyForDownload(Long fileId, Long userId);
}
