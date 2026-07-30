package com.cloudinfra.file.service;

import com.cloudinfra.file.domain.FileContent;
import io.minio.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * MinIO 文件服务实现
 */
public class MinioFileService {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioFileService(MinioClient minioClient, String bucketName) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    public String upload(FileContent content) throws IOException {
        try {
            String fileName = System.currentTimeMillis() + "_" + content.fileName();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(content.inputStream(), content.size(), -1)
                    .contentType(content.contentType())
                    .build());
            return fileName;
        } catch (Exception e) {
            throw new IOException("MinIO upload failed", e);
        }
    }

    public boolean delete(String filePath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(filePath).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
