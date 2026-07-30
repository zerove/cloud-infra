package com.cloudinfra.file.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.file.util.FileValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Tag(name = "文件管理")
@Slf4j
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {
    private final IFileService fileService;
    private final FileValidator fileValidator;

    @Operation(summary = "上传文件")
    @PostMapping("/upload")
    public Result<FileMetadataVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            // 1. 生产级安全校验
            fileValidator.validate(file);

            // 2. 转换为领域对象
            FileContent content = new FileContent(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream()
            );
            
            // 3. 调用业务服务（含配额校验 + 物理存储 + 元数据记录）
            FileMetadataVO metadata = fileService.upload(userId, content);
            return Result.success(metadata);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail(500, "文件上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "下载文件")
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable Long fileId, @RequestAttribute("userId") Long userId) {
        try {
            InputStream is = fileService.download(fileId, userId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @Operation(summary = "删除文件")
    @DeleteMapping("/delete/{fileId}")
    public Result<Void> delete(@PathVariable Long fileId) {
        if (fileService.delete(fileId)) {
            return Result.success();
        }
        return Result.fail(500, "文件删除失败");
    }
}