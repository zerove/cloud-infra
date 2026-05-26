package com.cloudinfra.file.service;

import io.minio.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;

/**
 * MinIO 文件服务实现
 */
public class MinioFileService implements IFileService {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioFileService(MinioClient minioClient, String bucketName) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    @Override
    public String upload(MultipartFile file) throws IOException {
        try {
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return fileName;
        } catch (Exception e) {
            throw new IOException("MinIO upload failed", e);
        }
    }

    @Override
    public boolean delete(String filePath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(filePath).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
