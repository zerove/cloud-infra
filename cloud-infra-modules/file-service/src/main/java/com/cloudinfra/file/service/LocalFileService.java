package com.cloudinfra.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
public class LocalFileService {

    private final String uploadPath = "uploads";

    public String upload(MultipartFile file) throws IOException {
        Path path = Paths.get(uploadPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        File dest = new File(path.toAbsolutePath().toString() + File.separator + fileName);
        file.transferTo(dest);

        log.info("File uploaded to: {}", dest.getAbsolutePath());
        return dest.getAbsolutePath();
    }
}
