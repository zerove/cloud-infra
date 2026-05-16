package com.cloudinfra.file;

import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

public class FileServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IFileService fileService;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Test
    public void testUploadAndMetadataPersistence() throws Exception {
        String fileName = "test.txt";
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/plain", "Hello World".getBytes());

        String fileUrl = fileService.upload(file);

        assertThat(fileUrl).isNotNull();
        assertThat(fileUrl).contains(fileName);

        // 验证数据库元数据
        var metadata = fileMetadataMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FileMetadata>()
                        .eq("file_path", fileUrl)
        );

        assertThat(metadata).isNotNull();
        assertThat(metadata.getFileName()).isEqualTo(fileName);
        assertThat(metadata.getFileSize()).isEqualTo(file.getSize());
    }
}
