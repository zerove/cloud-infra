package com.cloudinfra.file;

import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.StorageQuota;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.StorageQuotaMapper;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters(FileServiceContextExcludeFilter.class)
@SpringBootTest(classes = com.cloudinfra.file.FileApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
public class FileServiceIntegrationTest extends BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private IFileService fileService;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private StorageQuotaMapper storageQuotaMapper;

    @BeforeEach
    void setUp() {
        // 为测试用户初始化存储配额
        StorageQuota quota = new StorageQuota();
        quota.setId(1L);
        quota.setUserId(1L);
        quota.setMaxSizeBytes(1024L * 1024 * 1024); // 1GB
        quota.setUsedSizeBytes(0L);
        storageQuotaMapper.insert(quota);
    }

    @Test
    public void testUploadAndMetadataPersistence() throws Exception {
        String fileName = "test.txt";
        byte[] contentBytes = "Hello World".getBytes();
        FileContent fileContent = new FileContent(
            fileName, 
            "text/plain", 
            contentBytes.length, 
            new java.io.ByteArrayInputStream(contentBytes)
        );

        FileMetadataVO vo = fileService.upload(1L, fileContent);

        assertThat(vo).isNotNull();
        assertThat(vo.fileName()).isEqualTo(fileName);

        // 验证数据库元数据
        FileMetadata dbMetadata = fileMetadataMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FileMetadata>()
                        .eq("file_path", vo.storagePath())
        );

        assertThat(dbMetadata).isNotNull();
        assertThat(dbMetadata.getFileName()).isEqualTo(fileName);
        assertThat(dbMetadata.getFileSize()).isEqualTo(contentBytes.length);
    }
}
