package com.cloudinfra.file.service.strategy;

import com.cloudinfra.file.service.StorageStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

@Component("localStorageStrategy")
public class LocalStorageStrategy implements StorageStrategy {

    @Value("${file.storage.local.path:/tmp/cloud-infra/uploads}")
    private String baseDir;

    @Override
    public String upload(String fileName, InputStream inputStream) throws IOException {
        // 1. 确保目录存在
        Path root = Paths.get(baseDir);
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        // 2. 生成唯一文件名，防止覆盖
        String extension = getFileExtension(fileName);
        String storedName = UUID.randomUUID().toString() + extension;
        Path targetPath = root.resolve(storedName);

        // 3. 流式写入，避免内存溢出
        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

        return targetPath.toString();
    }

    @Override
    public InputStream download(String path) throws IOException {
        Path targetPath = Paths.get(path);
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException("文件不存在: " + path);
        }
        return Files.newInputStream(targetPath);
    }

    @Override
    public boolean delete(String path) {
        try {
            return Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
