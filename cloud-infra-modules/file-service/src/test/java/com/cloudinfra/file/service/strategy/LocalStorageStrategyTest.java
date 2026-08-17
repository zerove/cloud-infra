package com.cloudinfra.file.service.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地存储策略测试 - 覆盖上传/下载/删除/存在性与扩展名处理
 */
class LocalStorageStrategyTest {

    @TempDir
    Path tempDir;

    private LocalStorageStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LocalStorageStrategy();
        ReflectionTestUtils.setField(strategy, "baseDir", tempDir.toString());
    }

    @Test
    void upload_shouldCreateDirectoryAndReturnStoredPath() throws Exception {
        // given
        ByteArrayInputStream input = new ByteArrayInputStream("hello".getBytes());

        // when
        String storedPath = strategy.upload("report.pdf", input);

        // then
        assertThat(storedPath).startsWith(tempDir.toString());
        assertThat(storedPath).endsWith(".pdf");
        assertThat(Files.exists(Path.of(storedPath))).isTrue();
    }

    @Test
    void upload_withoutExtension_shouldKeepUuidName() throws Exception {
        // when
        String storedPath = strategy.upload("README", new ByteArrayInputStream("x".getBytes()));

        // then
        assertThat(storedPath).startsWith(tempDir.toString());
        assertThat(storedPath).doesNotEndWith(".");
        assertThat(Files.exists(Path.of(storedPath))).isTrue();
    }

    @Test
    void upload_withNullFileName_shouldStoreWithoutExtension() throws Exception {
        // when
        String storedPath = strategy.upload(null, new ByteArrayInputStream("x".getBytes()));

        // then
        assertThat(Files.exists(Path.of(storedPath))).isTrue();
    }

    @Test
    void download_existingFile_shouldReturnStream() throws Exception {
        // given
        String storedPath = strategy.upload("doc.txt", new ByteArrayInputStream("content".getBytes()));

        // when
        try (InputStream result = strategy.download(storedPath)) {
            // then
            assertThat(result.readAllBytes()).isEqualTo("content".getBytes());
        }
    }

    @Test
    void download_missingFile_shouldThrowFileNotFound() {
        assertThatThrownBy(() -> strategy.download(tempDir.resolve("missing.txt").toString()))
            .isInstanceOf(FileNotFoundException.class)
            .hasMessageContaining("文件不存在");
    }

    @Test
    void delete_existingFile_shouldReturnTrueAndRemove() throws Exception {
        // given
        String storedPath = strategy.upload("del.txt", new ByteArrayInputStream("x".getBytes()));

        // when
        boolean deleted = strategy.delete(storedPath);

        // then
        assertThat(deleted).isTrue();
        assertThat(Files.exists(Path.of(storedPath))).isFalse();
    }

    @Test
    void delete_missingFile_shouldReturnFalse() {
        assertThat(strategy.delete(tempDir.resolve("ghost.txt").toString())).isFalse();
    }

    @Test
    void exists_shouldReflectFilePresence() throws Exception {
        // given
        String storedPath = strategy.upload("probe.txt", new ByteArrayInputStream("x".getBytes()));

        // then
        assertThat(strategy.exists(storedPath)).isTrue();
        assertThat(strategy.exists(tempDir.resolve("nope.txt").toString())).isFalse();
    }
}