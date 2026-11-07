package com.cloudinfra.file.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 预签名 URL 生成。
 *
 * <p>{@code FileController} 此前直接注入 {@link MinioClient} 并把桶名<b>硬编码</b>为
 * {@code "cloud-infra-files"}，而 {@code MinioStorageStrategy} 读的是
 * {@code file.storage.minio.bucket}（默认 {@code cloud-infra}）。两处独立取值，
 * 只靠一条反射守护测试保证它们相等 —— 一旦有人改了配置而没同步控制器，
 * 就会"预签名写进 A 桶、实际存储落到 B 桶"。本类把该取值收敛为唯一来源。
 */
@Service
@RequiredArgsConstructor
public class FileStorageUrlService {

    private final MinioClient minioClient;

    /** 与 MinioStorageStrategy 同源，保证"签名的桶"与"写入的桶"永远一致。 */
    @Value("${file.storage.minio.bucket:cloud-infra}")
    private String bucketName;

    /** 上传直传 PUT 预签名。 */
    public String presignPut(String objectKey, int expirySeconds) {
        return presign(objectKey, Method.PUT, expirySeconds);
    }

    /** 下载直传 GET 预签名。 */
    public String presignGet(String objectKey, int expirySeconds) {
        return presign(objectKey, Method.GET, expirySeconds);
    }

    private String presign(String objectKey, Method method, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(method)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException("生成预签名 URL 失败: " + objectKey, e);
        }
    }
}