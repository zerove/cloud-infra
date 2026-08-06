package com.cloudinfra.file.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * MinIO 对象存储客户端配置
 * 提供 MinioClient Bean 供 MinioStorageStrategy 使用
 */
@Configuration
public class MinioConfig {

    @Bean
    @ConfigurationProperties(prefix = "file.storage.minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        properties.validate();
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * MinIO 连接配置属性
     */
    public static class MinioProperties {
        /**
         * MinIO 服务端点, 如 http://localhost:9000
         */
        private String endpoint = "http://localhost:9000";

        /**
         * 访问密钥（默认空，必须通过 MINIO_ACCESS_KEY 或配置注入）
         */
        private String accessKey;

        /**
         * 秘密密钥（默认空，必须通过 MINIO_SECRET_KEY 或配置注入）
         */
        private String secretKey;

        /**
         * 默认存储桶名称
         */
        private String bucket = "cloud-infra";

        /**
         * 启动校验：禁止使用公开默认凭证，禁止缺失凭证。
         * 生产环境必须通过 MINIO_ACCESS_KEY / MINIO_SECRET_KEY（或 file.storage.minio.*）注入强凭证。
         */
        public void validate() {
            if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
                throw new IllegalStateException(
                    "MinIO 凭证未配置：请通过环境变量 MINIO_ACCESS_KEY / MINIO_SECRET_KEY "
                    + "或配置 file.storage.minio.access-key / secret-key 设置独立凭证"
                );
            }
            if ("minioadmin".equals(accessKey) && "minioadmin".equals(secretKey)) {
                throw new IllegalStateException(
                    "MinIO 使用公开默认凭证 minioadmin/minioadmin，禁止用于任何环境："
                    + "请配置独立高强度凭证"
                );
            }
        }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
    }
}
