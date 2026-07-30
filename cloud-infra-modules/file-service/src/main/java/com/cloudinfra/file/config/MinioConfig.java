package com.cloudinfra.file.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
         * 访问密钥
         */
        private String accessKey = "minioadmin";

        /**
         * 秘密密钥
         */
        private String secretKey = "minioadmin";

        /**
         * 默认存储桶名称
         */
        private String bucket = "cloud-infra";

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
