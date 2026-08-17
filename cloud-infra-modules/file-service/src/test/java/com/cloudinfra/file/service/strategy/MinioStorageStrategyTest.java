package com.cloudinfra.file.service.strategy;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Minio 存储策略测试 - 使用 Mockito mock MinioClient 覆盖成功/失败路径
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageStrategyTest {

    @Mock
    private MinioClient minioClient;

    private MinioStorageStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MinioStorageStrategy(minioClient);
        ReflectionTestUtils.setField(strategy, "bucketName", "cloud-infra");
    }

    @Test
    void upload_shouldReturnObjectNameWithExtension() throws Exception {
        // when
        String objectName = strategy.upload("photo.png", new ByteArrayInputStream("img".getBytes()));

        // then
        assertThat(objectName).endsWith(".png");
    }

    @Test
    void upload_withoutExtension_shouldReturnUuidName() throws Exception {
        // when
        String objectName = strategy.upload("README", new ByteArrayInputStream("x".getBytes()));

        // then
        assertThat(objectName).doesNotEndWith(".");
    }

    @Test
    void upload_whenMinioFails_shouldWrapInIOException() throws Exception {
        // given
        doThrow(new RuntimeException("minio down")).when(minioClient).putObject(any(PutObjectArgs.class));

        // when / then
        assertThatThrownBy(() -> strategy.upload("a.txt", new ByteArrayInputStream("x".getBytes())))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Minio 上传失败");
    }

    @Test
    void download_shouldReturnStream() throws Exception {
        // given
        GetObjectResponse expected = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(expected);

        // when
        InputStream result = strategy.download("obj");

        // then
        assertThat(result).isSameAs(expected);
    }

    @Test
    void download_whenMinioFails_shouldWrapInIOException() throws Exception {
        // given
        doThrow(new RuntimeException("minio down")).when(minioClient).getObject(any(GetObjectArgs.class));

        // when / then
        assertThatThrownBy(() -> strategy.download("obj"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Minio 下载失败");
    }

    @Test
    void delete_shouldReturnTrueWhenSucceeds() throws Exception {
        // when
        boolean deleted = strategy.delete("obj");

        // then
        assertThat(deleted).isTrue();
    }

    @Test
    void delete_whenMinioFails_shouldReturnFalse() throws Exception {
        // given
        doThrow(new RuntimeException("minio down")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        // when
        boolean deleted = strategy.delete("obj");

        // then
        assertThat(deleted).isFalse();
    }

    @Test
    void exists_shouldReturnTrueWhenObjectExists() throws Exception {
        // given
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));

        // when
        boolean exists = strategy.exists("obj");

        // then
        assertThat(exists).isTrue();
    }

    @Test
    void exists_whenMinioFails_shouldReturnFalse() throws Exception {
        // given
        doThrow(new RuntimeException("minio down")).when(minioClient).statObject(any(StatObjectArgs.class));

        // when
        boolean exists = strategy.exists("obj");

        // then
        assertThat(exists).isFalse();
    }
}