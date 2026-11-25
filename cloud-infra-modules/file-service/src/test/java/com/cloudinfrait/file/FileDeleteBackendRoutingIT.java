package com.cloudinfrait.file;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudinfra.file.FileApplication;
import com.cloudinfra.file.FileServiceContextExcludeFilter;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.domain.StorageQuota;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import com.cloudinfra.file.mapper.StorageQuotaMapper;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.file.service.StorageStrategy;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 端到端验证 {@code delete()} 按**真正持有该文件的后端**删除, 而非当前默认策略。
 *
 * <p>缺失这一项时, {@code FileDeleteStrategyRoutingTest} 只能靠 mock 证明
 * "调用了 resolveForRead", 证明不了"删掉的确实是那个对象" —— 而后者才是修复的目的。
 * 本类让 delete() 走真实的 StorageFactory 装配、真实的策略实现与真实数据库事务。
 */
@Testcontainers
@org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters(FileServiceContextExcludeFilter.class)
@SpringBootTest(classes = FileApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
@Import(FileDeleteBackendRoutingIT.FakeBackendConfig.class)
class FileDeleteBackendRoutingIT extends BaseIntegrationTest {

    /** 只存在于假后端、本地后端永远没有的路径。 */
    private static final String FOREIGN_PATH = "/foreign-only/does-not-exist-locally.bin";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(3));

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

    @MockitoBean
    private FilePermissionService permissionService;

    @Autowired
    private FakeBackend fakeBackend;

    @BeforeEach
    void setUp() {
        fakeBackend.reset();
        given(permissionService.hasAccess(anyLong(), anyLong(), eq("WRITE"))).willReturn(true);

        StorageQuota quota = new StorageQuota();
        quota.setId(1L);
        quota.setUserId(1L);
        quota.setMaxSizeBytes(1024L * 1024 * 1024);
        quota.setUsedSizeBytes(0L);
        storageQuotaMapper.insert(quota);
    }

    /**
     * 核心用例: 文件只存在于非默认后端。默认策略是 localStorageStrategy, 因此
     * 若 delete() 退回 {@code getStrategy()}, 它会对本地后端删除一个不存在的路径 →
     * 返回 false → 抛 SYSTEM_ERROR。用例直接断言"对象真的从它所在的后端消失了"。
     */
    @Test
    void deletesFromTheBackendThatActuallyHoldsTheObject() {
        fakeBackend.hold(FOREIGN_PATH);

        FileMetadata metadata = new FileMetadata();
        metadata.setId(9001L);
        metadata.setUploader("1");
        metadata.setFileName("foreign.bin");
        metadata.setFilePath(FOREIGN_PATH);
        metadata.setFileSize(10L);
        metadata.setFileType("application/octet-stream");
        fileMetadataMapper.insert(metadata);

        boolean deleted = fileService.delete(9001L, 1L);

        assertThat(deleted).isTrue();
        assertThat(fakeBackend.holds(FOREIGN_PATH))
                .as("对象必须真的从它实际所在的后端消失, 而非只是返回 true")
                .isFalse();

        FileMetadata after = fileMetadataMapper.selectById(9001L);
        assertThat(after).as("DB 记录必须同步删除").isNull();
    }

    /**
     * 反向用例: 对象所在后端的 delete 返回 false 时必须抛错, 且**不得**删掉 DB 记录,
     * 否则会留下"记录没了、文件还在"的幽灵文件。
     */
    @Test
    void storageFailureThrowsAndKeepsTheRow() {
        fakeBackend.hold(FOREIGN_PATH);
        fakeBackend.failDelete(true);

        FileMetadata metadata = new FileMetadata();
        metadata.setId(9002L);
        metadata.setUploader("1");
        metadata.setFileName("foreign2.bin");
        metadata.setFilePath(FOREIGN_PATH);
        metadata.setFileSize(10L);
        metadata.setFileType("application/octet-stream");
        fileMetadataMapper.insert(metadata);

        assertThatThrownBy(() -> fileService.delete(9002L, 1L))
                .isInstanceOf(com.cloudinfra.common.core.exception.BusinessException.class)
                .hasMessageContaining("存储删除失败");

        assertThat(fileMetadataMapper.selectById(9002L))
                .as("存储删除失败时 DB 记录必须保留")
                .isNotNull();
    }

    @TestConfiguration
    static class FakeBackendConfig {

        @Bean("fakeStorageStrategy")
        FakeBackend fakeStorageStrategy() {
            return new FakeBackend();
        }
    }

    /** 内存后端: 仅用于验证"按真实持有者路由", 不参与 upload 的大小路由。 */
    static class FakeBackend implements StorageStrategy {

        private final Set<String> paths = ConcurrentHashMap.newKeySet();
        private volatile boolean failDelete;

        void reset() {
            paths.clear();
            failDelete = false;
        }

        void hold(String path) {
            paths.add(path);
        }

        boolean holds(String path) {
            return paths.contains(path);
        }

        void failDelete(boolean value) {
            this.failDelete = value;
        }

        @Override
        public String upload(String fileName, InputStream inputStream) {
            paths.add(fileName);
            return fileName;
        }

        @Override
        public InputStream download(String path) throws IOException {
            if (!paths.contains(path)) {
                throw new IOException("not found: " + path);
            }
            return InputStream.nullInputStream();
        }

        @Override
        public boolean delete(String path) {
            return !failDelete && paths.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return paths.contains(path);
        }
    }
}