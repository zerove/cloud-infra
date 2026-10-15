package com.cloudinfra.file;

import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.FileVersionMapper;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.service.StorageFactory;
import com.cloudinfra.file.service.StorageQuotaService;
import com.cloudinfra.file.service.StorageStrategy;
import com.cloudinfra.file.service.impl.FileServiceImpl;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * delete() 的三条不变量:
 * 1. 按**真正持有该文件的后端**删除, 而非当前默认策略
 * 2. 存储删除失败 → 抛错且**不落库**(不产生"记录没了、文件还在"的幽灵文件)
 * 3. 元数据不存在 → 404; 无权限 → 403(顺序不可颠倒, 否则泄漏资源存在性)
 */
class FileDeleteStrategyRoutingTest {

    private static final class RecordingStrategy implements StorageStrategy {
        private final String name;
        private final boolean holds;
        private final boolean deleteSucceeds;
        private boolean deleteCalled;
        private String deletedPath;

        private RecordingStrategy(String name, boolean holds, boolean deleteSucceeds) {
            this.name = name;
            this.holds = holds;
            this.deleteSucceeds = deleteSucceeds;
        }

        @Override public String upload(String f, InputStream i) { return name + ":" + f; }
        @Override public InputStream download(String p) { return InputStream.nullInputStream(); }
        @Override public boolean exists(String p) { return holds; }

        @Override
        public boolean delete(String path) {
            deleteCalled = true;
            deletedPath = path;
            return deleteSucceeds;
        }
    }

    private static FileMetadata metadata(String path, Long size) {
        FileMetadata m = new FileMetadata();
        m.setId(42L);
        m.setFilePath(path);
        m.setFileName("big.txt");
        m.setFileSize(size);
        m.setUploader("1");
        return m;
    }

    /** TransactionTemplate 的 mock 必须真正执行回调: mock 对 execute() 返回 null 而非回调结果。 */
    private static TransactionTemplate executingTemplate() {
        TransactionTemplate t = mock(TransactionTemplate.class);
        when(t.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        return t;
    }

    private static FileServiceImpl service(FileMetadata meta, FileMetadataMapper mapper,
                                          StorageStrategy resolved, StorageStrategy deflt) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.resolveForRead(any())).thenReturn(resolved);
        when(factory.getStrategy()).thenReturn(deflt);
        when(mapper.selectById(meta.getId())).thenReturn(meta);
        when(mapper.deleteById(meta.getId())).thenReturn(1);
        FilePermissionService perms = mock(FilePermissionService.class);
        when(perms.hasAccess(anyLong(), anyLong(), eq("WRITE"))).thenReturn(true);
        return new FileServiceImpl(factory, mapper, mock(FileVersionMapper.class),
                perms, mock(StorageQuotaService.class), executingTemplate());
    }

    @Test
    @DisplayName("必须按真正持有该文件的后端删除, 而非当前默认策略")
    void deletesViaBackendThatActuallyHoldsTheFile() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", true, true);
        RecordingStrategy local = new RecordingStrategy("localStorageStrategy", false, true);
        FileMetadata meta = metadata("obj-uuid.txt", 20L * 1024 * 1024);
        FileMetadataMapper mapper = mock(FileMetadataMapper.class);

        boolean ok = service(meta, mapper, minio, local).delete(42L, 1L);

        assertThat(ok).as("删除应成功").isTrue();
        assertThat(minio.deleteCalled)
                .as("文件在 MinIO 就必须走 MinIO 策略 —— 用默认 local 会静默删不掉")
                .isTrue();
        assertThat(minio.deletedPath).isEqualTo("obj-uuid.txt");
        assertThat(local.deleteCalled).as("默认策略不应被调用").isFalse();
    }

    @Test
    @DisplayName("存储删除失败必须抛错且不落库, 不产生幽灵文件")
    void storageDeleteFailureThrowsAndSkipsDb() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", true, false);
        RecordingStrategy local = new RecordingStrategy("localStorageStrategy", false, true);
        FileMetadata meta = metadata("obj.txt", 10L);
        FileMetadataMapper mapper = mock(FileMetadataMapper.class);

        assertThatThrownBy(() -> service(meta, mapper, minio, local).delete(42L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));

        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("元数据不存在返回 404 而非 403")
    void missingMetadataYields404Not403() {
        FileMetadataMapper mapper = mock(FileMetadataMapper.class);
        when(mapper.selectById(42L)).thenReturn(null);
        StorageFactory factory = mock(StorageFactory.class);
        FileServiceImpl svc = new FileServiceImpl(factory, mapper, mock(FileVersionMapper.class),
                mock(FilePermissionService.class), mock(StorageQuotaService.class), executingTemplate());

        assertThatThrownBy(() -> svc.delete(42L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("无写权限返回 403 且不触碰存储")
    void missingWritePermissionYields403() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", true, true);
        FileMetadata meta = metadata("obj.txt", 10L);
        FileMetadataMapper mapper = mock(FileMetadataMapper.class);
        when(mapper.selectById(42L)).thenReturn(meta);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.resolveForRead(any())).thenReturn(minio);
        FilePermissionService perms = mock(FilePermissionService.class);
        when(perms.hasAccess(anyLong(), anyLong(), eq("WRITE"))).thenReturn(false);

        FileServiceImpl svc = new FileServiceImpl(factory, mapper, mock(FileVersionMapper.class),
                perms, mock(StorageQuotaService.class), executingTemplate());

        assertThatThrownBy(() -> svc.delete(42L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.NO_PERMISSION.getCode()));
        assertThat(minio.deleteCalled).as("无权限时不得触碰存储").isFalse();
    }
}
