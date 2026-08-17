package com.cloudinfra.file.service.impl;

import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.FileVersion;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.FileVersionMapper;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.service.StorageFactory;
import com.cloudinfra.file.service.StorageQuotaService;
import com.cloudinfra.file.service.StorageStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock private StorageFactory storageFactory;
    @Mock private FileMetadataMapper metadataMapper;
    @Mock private FileVersionMapper versionMapper;
    @Mock private FilePermissionService permissionService;
    @Mock private StorageQuotaService quotaService;
    @Mock private StorageStrategy storageStrategy;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(
            storageFactory, metadataMapper, versionMapper,
            permissionService, quotaService
        );
    }

    @Test
    void upload_shouldPersistMetadataAndReturnVO() throws Exception {
        // given
        Long userId = 1L;
        FileContent content = new FileContent("test.txt", "text/plain", 100L, new ByteArrayInputStream("hello".getBytes()));

        given(storageFactory.getStrategy(content.size(), content.contentType())).willReturn(storageStrategy);
        given(storageStrategy.upload(content.fileName(), content.inputStream())).willReturn("/uploads/test.txt");
        given(metadataMapper.insert(any(FileMetadata.class))).willAnswer(invocation -> {
            FileMetadata meta = invocation.getArgument(0);
            meta.setId(42L);
            return 1;
        });

        // when
        FileMetadataVO result = fileService.upload(userId, content);

        // then
        assertThat(result).isNotNull();
        assertThat(result.fileName()).isEqualTo("test.txt");
        assertThat(result.fileId()).isEqualTo(42L);
        assertThat(result.storagePath()).isEqualTo("/uploads/test.txt");

        then(quotaService).should().checkQuota(userId, content.size());
        then(quotaService).should().tryReserve(userId, content.size());
        then(metadataMapper).should().insert(any(FileMetadata.class));
        then(versionMapper).should().insert(any(FileVersion.class));
    }

    @Test
    void upload_whenQuotaExceeded_shouldThrowException() {
        // given
        Long userId = 1L;
        FileContent content = new FileContent("big.txt", "text/plain", 999999L, new ByteArrayInputStream("data".getBytes()));

        doThrow(new com.cloudinfra.common.core.exception.BusinessException(
            com.cloudinfra.common.core.exception.ErrorCode.RESOURCE_EXHAUSTED, "quota exceeded"))
            .when(quotaService).checkQuota(userId, content.size());

        // when / then
        assertThatThrownBy(() -> fileService.upload(userId, content))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("quota exceeded");

        then(metadataMapper).should(never()).insert(any(FileMetadata.class));
        then(versionMapper).should(never()).insert(any(FileVersion.class));
    }

    @Test
    void download_withValidPermission_shouldReturnStream() throws Exception {
        // given
        Long fileId = 1L, userId = 10L;
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setFileName("doc.pdf");
        metadata.setFilePath("/uploads/doc.pdf");
        metadata.setFileSize(200L);

        given(metadataMapper.selectById(fileId)).willReturn(metadata);
        given(permissionService.hasAccess(userId, fileId, "READ")).willReturn(true);
        given(storageFactory.getStrategy()).willReturn(storageStrategy);
        given(storageStrategy.download(metadata.getFilePath())).willReturn(new ByteArrayInputStream("pdf content".getBytes()));

        // when
        InputStream result = fileService.download(fileId, userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.readAllBytes()).isEqualTo("pdf content".getBytes());
    }

    @Test
    void download_withoutPermission_shouldThrowException() {
        // given
        Long fileId = 1L, userId = 10L;
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);

        given(metadataMapper.selectById(fileId)).willReturn(metadata);
        given(permissionService.hasAccess(userId, fileId, "READ")).willReturn(false);

        // when / then
        assertThatThrownBy(() -> fileService.download(fileId, userId))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("permission");
    }

    @Test
    void download_whenFileNotFound_shouldThrowException() {
        // given
        given(metadataMapper.selectById(anyLong())).willReturn(null);

        // when / then
        assertThatThrownBy(() -> fileService.download(99L, 1L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void delete_existingFile_shouldReturnTrue() {
        // given
        Long fileId = 1L;
        Long userId = 10L;
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setFilePath("/uploads/doc.pdf");

        given(metadataMapper.selectById(fileId)).willReturn(metadata);
        given(permissionService.hasAccess(userId, fileId, "WRITE")).willReturn(true);
        given(storageFactory.getStrategy()).willReturn(storageStrategy);
        given(storageStrategy.delete(metadata.getFilePath())).willReturn(true);
        given(metadataMapper.deleteById(fileId)).willReturn(1);

        // when
        boolean result = fileService.delete(fileId, userId);

        // then
        assertThat(result).isTrue();
        then(versionMapper).should().delete(any());
    }

    @Test
    void delete_nonExistentFile_shouldReturnFalse() {
        // given
        given(metadataMapper.selectById(anyLong())).willReturn(null);

        // when
        boolean result = fileService.delete(99L, 1L);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void searchFiles_shouldReturnMatchingResults() {
        // given
        String keyword = "test";
        FileMetadata meta = new FileMetadata();
        meta.setId(1L);
        meta.setFileName("test.txt");
        meta.setFilePath("/uploads/test.txt");
        meta.setFileType("text/plain");
        meta.setFileSize(100L);
        meta.setCreateTime(new Date());

        given(metadataMapper.selectList(any())).willReturn(List.of(meta));

        // when
        List<FileMetadataVO> results = fileService.searchFiles(keyword);

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).fileName()).isEqualTo("test.txt");
        assertThat(results.get(0).url()).isEqualTo("/file/download/1");
    }

    @Test
    void update_existingFile_shouldPersistNewVersion() throws Exception {
        // given
        Long fileId = 1L, userId = 10L;
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setFileName("v1.txt");
        metadata.setFilePath("/uploads/v1.txt");
        metadata.setFileSize(100L);
        metadata.setCurrentVersion(1);

        FileContent content = new FileContent("v2.txt", "text/plain", 200L, new ByteArrayInputStream("more".getBytes()));

        given(metadataMapper.selectById(fileId)).willReturn(metadata);
        given(permissionService.hasAccess(userId, fileId, "WRITE")).willReturn(true);
        given(storageFactory.getStrategy(content.size(), content.contentType())).willReturn(storageStrategy);
        given(storageStrategy.upload(content.fileName(), content.inputStream())).willReturn("/uploads/v2.txt");

        // when
        FileMetadataVO result = fileService.update(fileId, content, userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.fileName()).isEqualTo("v1.txt");
        assertThat(result.storagePath()).isEqualTo("/uploads/v2.txt");
        assertThat(metadata.getCurrentVersion()).isEqualTo(2);
        then(quotaService).should().tryReserve(userId, 100L);
        then(metadataMapper).should().updateById(any(FileMetadata.class));
        then(versionMapper).should().insert(any(FileVersion.class));
    }

    @Test
    void update_whenFileNotFound_shouldThrowException() {
        // given
        given(metadataMapper.selectById(anyLong())).willReturn(null);
        FileContent content = new FileContent("x.txt", "text/plain", 10L, new ByteArrayInputStream("x".getBytes()));

        // when / then
        assertThatThrownBy(() -> fileService.update(1L, content, 1L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void update_withoutWritePermission_shouldThrowException() {
        // given
        FileMetadata metadata = new FileMetadata();
        metadata.setId(1L);
        given(metadataMapper.selectById(1L)).willReturn(metadata);
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(false);
        FileContent content = new FileContent("x.txt", "text/plain", 10L, new ByteArrayInputStream("x".getBytes()));

        // when / then
        assertThatThrownBy(() -> fileService.update(1L, content, 1L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("permission");
    }

    @Test
    void update_whenDeltaNotPositive_shouldNotReserveQuota() throws Exception {
        // given
        FileMetadata metadata = new FileMetadata();
        metadata.setId(1L);
        metadata.setFileName("v1.txt");
        metadata.setFilePath("/uploads/v1.txt");
        metadata.setFileSize(500L);
        metadata.setCurrentVersion(1);

        FileContent content = new FileContent("v2.txt", "text/plain", 300L, new ByteArrayInputStream("smaller".getBytes()));

        given(metadataMapper.selectById(1L)).willReturn(metadata);
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(true);
        given(storageFactory.getStrategy(content.size(), content.contentType())).willReturn(storageStrategy);
        given(storageStrategy.upload(content.fileName(), content.inputStream())).willReturn("/uploads/v2.txt");

        // when
        fileService.update(1L, content, 1L);

        // then
        then(quotaService).should(never()).tryReserve(anyLong(), anyLong());
    }

    @Test
    void downloadVersion_withValidPermission_shouldReturnStream() throws Exception {
        // given
        Long fileId = 1L, userId = 10L;
        FileVersion version = new FileVersion();
        version.setFileId(fileId);
        version.setVersionNumber(2);
        version.setStoragePath("/uploads/v2.txt");

        given(permissionService.hasAccess(userId, fileId, "READ")).willReturn(true);
        given(versionMapper.selectOne(any())).willReturn(version);
        given(storageFactory.getStrategy()).willReturn(storageStrategy);
        given(storageStrategy.download("/uploads/v2.txt")).willReturn(new ByteArrayInputStream("v2 content".getBytes()));

        // when
        InputStream result = fileService.downloadVersion(fileId, 2, userId);

        // then
        assertThat(result.readAllBytes()).isEqualTo("v2 content".getBytes());
    }

    @Test
    void downloadVersion_withoutPermission_shouldThrowException() {
        // given
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("READ"))).willReturn(false);

        // when / then
        assertThatThrownBy(() -> fileService.downloadVersion(1L, 2, 10L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("permission");
    }

    @Test
    void downloadVersion_whenVersionNotFound_shouldThrowException() {
        // given
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("READ"))).willReturn(true);
        given(versionMapper.selectOne(any())).willReturn(null);

        // when / then
        assertThatThrownBy(() -> fileService.downloadVersion(1L, 99, 10L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void getVersionHistory_shouldReturnVersionsDescending() {
        // given
        FileVersion v2 = new FileVersion();
        v2.setVersionNumber(2);
        FileVersion v1 = new FileVersion();
        v1.setVersionNumber(1);
        given(versionMapper.selectList(any())).willReturn(List.of(v2, v1));

        // when
        List<FileVersion> history = fileService.getVersionHistory(1L);

        // then
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getVersionNumber()).isEqualTo(2);
    }

    @Test
    void delete_withoutPermission_shouldThrowException() {
        // given
        FileMetadata metadata = new FileMetadata();
        metadata.setId(1L);
        metadata.setFilePath("/uploads/doc.pdf");
        given(metadataMapper.selectById(1L)).willReturn(metadata);
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(false);

        // when / then
        assertThatThrownBy(() -> fileService.delete(1L, 1L))
            .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
            .hasMessageContaining("permission");
    }

    @Test
    void delete_whenStorageDeleteFails_shouldReturnFalse() {
        // given
        FileMetadata metadata = new FileMetadata();
        metadata.setId(1L);
        metadata.setFilePath("/uploads/doc.pdf");
        given(metadataMapper.selectById(1L)).willReturn(metadata);
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(true);
        given(storageFactory.getStrategy()).willReturn(storageStrategy);
        given(storageStrategy.delete("/uploads/doc.pdf")).willReturn(false);

        // when
        boolean result = fileService.delete(1L, 1L);

        // then
        assertThat(result).isFalse();
        then(metadataMapper).should(never()).deleteById(anyLong());
    }

    @Test
    void delete_whenUploaderInvalid_shouldFallbackToCurrentUser() {
        // given
        FileMetadata metadata = new FileMetadata();
        metadata.setId(1L);
        metadata.setFilePath("/uploads/doc.pdf");
        metadata.setFileSize(50L);
        metadata.setUploader("not-a-number");
        given(metadataMapper.selectById(1L)).willReturn(metadata);
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(true);
        given(storageFactory.getStrategy()).willReturn(storageStrategy);
        given(storageStrategy.delete("/uploads/doc.pdf")).willReturn(true);
        given(metadataMapper.deleteById(1L)).willReturn(1);

        // when
        boolean result = fileService.delete(1L, 10L);

        // then
        assertThat(result).isTrue();
        then(quotaService).should().release(10L, 50L);
        then(versionMapper).should().delete(any());
    }
}
