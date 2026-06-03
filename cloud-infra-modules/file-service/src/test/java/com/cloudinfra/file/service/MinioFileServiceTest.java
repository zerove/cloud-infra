package com.cloudinfra.file.service;

import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileContent;
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
        
        byte[] bytes = "hello".getBytes();
        FileContent fileContent = new FileContent(
            "test.txt", 
            "text/plain", 
            bytes.length, 
            new java.io.ByteArrayInputStream(bytes)
        );
        String result = service.upload(fileContent);
        
        assertNotNull(result);
        verify(minioClient, times(1)).putObject(any());
    }
}
