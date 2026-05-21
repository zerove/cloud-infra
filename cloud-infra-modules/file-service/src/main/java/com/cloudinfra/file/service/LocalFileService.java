package com.cloudinfra.file.service;

import com.cloudinfra.file.domain.FileMetadata;
import com.cloudinfra.file.mapper.FileMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class LocalFileService implements IFileService {

    @Value("${file.upload-path:uploads}")
    private String uploadPath;

    private final FileMetadataMapper fileMetadataMapper;

    @Override
    public String upload(MultipartFile file) throws IOException {
        Path path = Paths.get(uploadPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        String originalFilename = file.getOriginalFilename();
        String fileName = UUID.randomUUID() + "_" + originalFilename;
        File dest = new File(path.toAbsolutePath().toString() + File.separator + fileName);
        file.transferTo(dest);

        FileMetadata metadata = new FileMetadata();
        metadata.setFileName(originalFilename);
        metadata.setFilePath("/files/" + fileName);
        metadata.setFileType(file.getContentType());
        metadata.setFileSize(file.getSize());
        metadata.setCreateTime(new Date());
        fileMetadataMapper.insert(metadata);

        log.info("文件已成功上传至本地: {}", dest.getAbsolutePath());
        return metadata.getFilePath(); 
    }

    @Override
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
