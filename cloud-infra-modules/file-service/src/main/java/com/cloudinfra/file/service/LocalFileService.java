package com.cloudinfra.file.service;

import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class LocalFileService {

    @Value("${file.upload-path:uploads}")
    private String uploadPath;

    private final FileMetadataMapper fileMetadataMapper;

    public String upload(FileContent content) throws IOException {
        Path path = Paths.get(uploadPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        String fileName = UUID.randomUUID() + "_" + content.fileName();
        Path destPath = path.resolve(fileName);
        Files.copy(content.inputStream(), destPath);

        FileMetadata metadata = new FileMetadata();
        metadata.setFileName(content.fileName());
        metadata.setFilePath("/files/" + fileName);
        metadata.setFileType(content.contentType());
        metadata.setFileSize(content.size());
        metadata.setCreateTime(new Date());
        fileMetadataMapper.insert(metadata);

        log.info("文件已成功上传至本地: {}", destPath.toAbsolutePath());
        return metadata.getFilePath(); 
    }

    public boolean delete(String filePath) {
        try {
            String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
            Path path = Paths.get(uploadPath, fileName);
            
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                fileMetadataMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FileMetadata>().eq("file_path", filePath));
            }
            return deleted;
        } catch (IOException e) {
            log.error("删除文件失败: {}", filePath, e);
            return false;
        }
    }
}
