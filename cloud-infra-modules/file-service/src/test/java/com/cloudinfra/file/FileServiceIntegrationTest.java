package com.cloudinfra.file;

import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.StorageQuota;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.StorageQuotaMapper;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

public class FileServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IFileService fileService;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private StorageQuotaMapper storageQuotaMapper;

    @BeforeEach
    void setUp() {
        // 为测试用户初始化存储配额
        StorageQuota quota = new StorageQuota();
        quota.setId(1L);
        quota.setUserId(1L);
        quota.setMaxSizeBytes(1024L * 1024 * 1024); // 1GB
        quota.setUsedSizeBytes(0L);
        storageQuotaMapper.insert(quota);
    }

    @Test
    public void testUploadAndMetadataPersistence() throws Exception {
        String fileName = "test.txt";
        byte[] contentBytes = "Hello World".getBytes();
        FileContent fileContent = new FileContent(
            fileName, 
            "text/plain", 
            contentBytes.length, 
            new java.io.ByteArrayInputStream(contentBytes)
        );

        FileMetadataVO vo = fileService.upload(1L, fileContent);

        assertThat(vo).isNotNull();
        assertThat(vo.fileName()).isEqualTo(fileName);

        // 验证数据库元数据
        FileMetadata dbMetadata = fileMetadataMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FileMetadata>()
                        .eq("file_path", vo.storagePath())
        );

        assertThat(dbMetadata).isNotNull();
        assertThat(dbMetadata.getFileName()).isEqualTo(fileName);
        assertThat(dbMetadata.getFileSize()).isEqualTo(contentBytes.length);
    }
}
