package com.cloudinfra.file.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.file.service.IFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "文件管理")
@Slf4j
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final IFileService fileService;

    @Operation(summary = "上传文件")
    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        try {
            String url = fileService.upload(file);
            Map<String, String> data = new HashMap<>();
            data.put("url", url);
            data.put("name", file.getOriginalFilename());
            return Result.success(data);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode(), "文件上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "删除文件")
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("path") String path) {
        if (fileService.delete(path)) {
            return Result.success();
        }
        return Result.fail(com.cloudinfra.common.core.exception.ErrorCode.SYSTEM_ERROR.getCode(), "文件删除失败");
    }
}
