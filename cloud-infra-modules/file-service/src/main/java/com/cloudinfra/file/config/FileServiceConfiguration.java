package com.cloudinfra.file.config;

import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.service.LocalFileService;
import com.cloudinfra.file.service.MinioFileService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileServiceConfiguration {

    @Bean
    @ConditionalOnProperty(name = "file.type", havingValue = "local", matchIfMissing = true)
    public LocalFileService localFileService(FileMetadataMapper fileMetadataMapper) {
        return new LocalFileService(fileMetadataMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "file.type", havingValue = "minio")
    public MinioFileService minioFileService(MinioClient minioClient, 
                                       @Value("${file.minio.bucket-name}") String bucketName) {
        return new MinioFileService(minioClient, bucketName);
    }
}
