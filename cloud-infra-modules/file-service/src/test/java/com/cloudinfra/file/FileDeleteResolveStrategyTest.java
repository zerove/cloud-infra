package com.cloudinfra.file;

import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.FileVersionMapper;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.service.StorageFactory;
import com.cloudinfra.file.service.StorageQuotaService;
import com.cloudinfra.file.service.StorageStrategy;
import com.cloudinfra.file.service.impl.FileServiceImpl;
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
 * 守护 <b>删除必须命中文件真实所在的存储</b>。
 *
 * <p><b>为什么单独一个测试</b>: {@link StorageStrategyResolveForReadTest} 验的是
 * 工厂「能不能解析出正确策略」，而本测试验的是
 * {@code FileServiceImpl.delete} 「<b>真的调用了那个策略</b>」。
 * 两者是不同的失效面 —— 工厂解析对了、调用方仍可能传错参数，
 * 或者像修复前那样压根不用 {@code resolveForRead}。
 *
 * <p><b>为什么必须带负向对照</b>: 若把 {@code resolveForRead} 改回
 * 无参 {@code getStrategy()}，本测试必须变红 ——
 * 否则「它绿」并不代表它在守护（第三课：守护的有效性只有负向对照能证明）。
 *
 * <p><b>2026-10-06 附带发现</b>: 删除链路<b>没有任何 HTTP 端点</b>
 * （控制器无 {@code @DeleteMapping}），即该能力目前无法从接口触达。
 * 本测试覆盖的是服务层逻辑，不等于端到端可达。
 */
class FileDeleteResolveStrategyTest {

    /** 记录自己是否被调用删除的假策略 */
    private static final class RecordingStrategy implements StorageStrategy {
        private final String name;
        private final boolean deleteSucceeds;
        boolean deleteCalled;
        String deletedPath;

        RecordingStrategy(String name, boolean deleteSucceeds) {
            this.name = name;
            this.deleteSucceeds = deleteSucceeds;
        }

        @Override
        public String upload(String fileName, InputStream inputStream) {
            return name + ":" + fileName;
        }

        @Override
        public InputStream download(String path) throws IOException {
            return InputStream.nullInputStream();
        }

        @Override
        public boolean delete(String path) {
            deleteCalled = true;
            deletedPath = path;
            return deleteSucceeds;
        }

        @Override
        public boolean exists(String path) {
            return name.equals("minioStorageStrategy");
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

    private FileServiceImpl serviceWith(FileMetadata meta,
                                        FileMetadataMapper metadataMapper,
                                        StorageStrategy strategy) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.resolveForRead(any())).thenReturn(strategy);

        FilePermissionService permissionService = mock(FilePermissionService.class);
        when(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).thenReturn(true);

        StorageQuotaService quotaService = mock(StorageQuotaService.class);
        FileVersionMapper versionMapper = mock(FileVersionMapper.class);

        when(metadataMapper.selectById(meta.getId())).thenReturn(meta);
        when(metadataMapper.deleteById(meta.getId())).thenReturn(1);

        // TransactionTemplate 必须真正执行回调: mock 默认对 execute() 返回 null,
        // 若不接线则业务代码读到的删除结果恒为 null, 测试会以"删除应成功"失败。
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        return new FileServiceImpl(factory, metadataMapper, versionMapper,
                permissionService, quotaService, transactionTemplate);
    }

    @Test
    @DisplayName("文件在 MinIO: 必须调用 MinIO 策略的 delete, 且传原始存储路径")
    void deleteHitsMinioStrategyForLargeFile() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", true);
        FileMetadata meta = metadata("obj-uuid.txt", 20L * 1024 * 1024);
        FileMetadataMapper metadataMapper = mock(FileMetadataMapper.class);

        FileServiceImpl service = serviceWith(meta, metadataMapper, minio);

        boolean result = service.delete(42L, 1L);

        assertThat(result).as("删除应成功").isTrue();
        assertThat(minio.deleteCalled)
                .as("MinIO 策略的 delete 必须被调用 —— 修复前此处恒调用 local, 大文件删不掉")
                .isTrue();
        assertThat(minio.deletedPath)
                .as("删除必须用元数据里的真实存储路径")
                .isEqualTo("obj-uuid.txt");
    }

    @Test
    @DisplayName("存储删除失败时不得删元数据 —— 否则留下'记录在、文件没了'的幽灵行")
    void keepsMetadataWhenStorageDeleteFails() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", false);
        FileMetadata meta = metadata("obj-uuid.txt", 20L * 1024 * 1024);
        FileMetadataMapper metadataMapper = mock(FileMetadataMapper.class);

        FileServiceImpl service = serviceWith(meta, metadataMapper, minio);

        // 2026-10-07: 原先断言 isFalse()。"文件不存在"与"存储删除失败"都返回 false,
        // HTTP 层无法区分 —— 调用方会把仍在存储里的文件当成已删除。
        // 现改为抛 SYSTEM_ERROR, 元数据保留。
        assertThatThrownBy(() -> service.delete(42L, 1L))
                .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
                .extracting(e -> ((com.cloudinfra.common.core.exception.BusinessException) e).getCode())
                .isEqualTo(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode());

        verify(metadataMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("无权限时不得触碰任何存储")
    void noStorageTouchedWithoutPermission() {
        RecordingStrategy minio = new RecordingStrategy("minioStorageStrategy", true);
        FileMetadata meta = metadata("obj-uuid.txt", 1024L);

        StorageFactory factory = mock(StorageFactory.class);
        when(factory.resolveForRead(any())).thenReturn(minio);
        FilePermissionService permissionService = mock(FilePermissionService.class);
        when(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).thenReturn(false);
        FileMetadataMapper metadataMapper = mock(FileMetadataMapper.class);
        when(metadataMapper.selectById(42L)).thenReturn(meta);

        FileServiceImpl service = new FileServiceImpl(factory, metadataMapper,
                mock(FileVersionMapper.class), permissionService,
                mock(StorageQuotaService.class), mock(TransactionTemplate.class));

        assertThatThrownBy(() -> service.delete(42L, 1L))
                .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class);

        assertThat(minio.deleteCalled).as("无权限时绝不能删除存储对象").isFalse();
        verify(factory, never()).resolveForRead(any());
    }
}
