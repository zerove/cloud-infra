package com.cloudinfra.file.service;

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
import java.util.UUID;

@Slf4j
@Primary
@Service
public class LocalFileService implements IFileService {

    @Value("${file.upload-path:uploads}")
    private String uploadPath;

    @Override
    public String upload(MultipartFile file) throws IOException {
        Path path = Paths.get(uploadPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        File dest = new File(path.toAbsolutePath().toString() + File.separator + fileName);
        file.transferTo(dest);

        log.info("文件已成功上传至本地: {}", dest.getAbsolutePath());
        return "/files/" + fileName; // 返回相对 URL
    }

    @Override
    public boolean delete(String filePath) {
        try {
            String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
            Path path = Paths.get(uploadPath, fileName);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("删除文件失败: {}", filePath, e);
            return false;
        }
    }
}
