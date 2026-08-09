package com.cloudinfra.file.service.strategy;

import com.cloudinfra.file.service.StorageStrategy;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Component("minioStorageStrategy")
public class MinioStorageStrategy implements StorageStrategy {

    private final MinioClient minioClient;
    
    @Value("${file.storage.minio.bucket:cloud-infra}")
    private String bucketName;

    public MinioStorageStrategy(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public String upload(String fileName, InputStream inputStream) throws IOException {
        // 1. 生成唯一对象名称
        String extension = getFileExtension(fileName);
        String objectName = UUID.randomUUID().toString() + extension;

        try {
            // 2. 使用 putObject 上传流
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, -1, 10485760) // 10MB part size
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Minio 上传失败: " + e.getMessage(), e);
        }

        return objectName;
    }

    @Override
    public InputStream download(String path) throws IOException {
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Minio 下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String path) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        try {
            return minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(path)
                    .build()
            ) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
