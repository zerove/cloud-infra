package com.cloudinfra.file.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class MinioFileServiceTest {

    @Test
    public void testUpload() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioFileService service = new MinioFileService(minioClient, "test-bucket");
        
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());
        String result = service.upload(file);
        
        assertNotNull(result);
        verify(minioClient, times(1)).putObject(any());
    }
}
