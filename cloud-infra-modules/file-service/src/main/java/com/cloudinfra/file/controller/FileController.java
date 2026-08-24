package com.cloudinfra.file.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.api.file.dto.FileVersion;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.util.FileValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

@Tag(name = "文件管理")
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileController.class);
    private final IFileService fileService;
    private final FileValidator fileValidator;
    private final FilePermissionService permissionService;

    @Operation(summary = "上传文件")
    @PreAuthorize("@ss.hasPermi('file:file:upload')")
    @PostMapping("/upload")
    public Result<FileMetadataVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            // 生产级安全校验
            fileValidator.validate(file);

            // 从安全上下文解析用户（支持网关注入 Header 与直连两种模式）
            Long userId = resolveUserId(requestUserId);

            // 转换为领域对象
            FileContent content = new FileContent(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream()
            );
            
            // 调用业务服务（含配额校验 + 物理存储 + 元数据记录）
            FileMetadataVO metadata = fileService.upload(userId, content);
            return Result.success(metadata);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail(500, "文件上传失败");
        }
    }

    /**
     * 解析当前用户 ID：优先 RequestAttribute（网关注入），回退 SecurityContext（直连），
     * 均无则抛 401。
     */
    private Long resolveUserId(Long requestUserId) {
        if (requestUserId != null) {
            return requestUserId;
        }
        Long securityUserId = com.cloudinfra.common.util.SecurityUtils.getUserId();
        if (securityUserId != null) {
            return securityUserId;
        }
        throw new IllegalArgumentException("未认证的用户无法访问文件服务");
    }

    @Operation(summary = "更新文件（创建新版本）")
    @PreAuthorize("@ss.hasPermi('file:file:update')")
    @PutMapping("/update/{fileId}")
    public Result<FileMetadataVO> update(
            @PathVariable Long fileId,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            fileValidator.validate(file);
            Long userId = resolveUserId(requestUserId);
            FileContent content = new FileContent(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream()
            );
            return Result.success(fileService.update(fileId, content, userId));
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("文件更新失败", e);
            return Result.fail(500, "文件更新失败");
        }
    }

    @Operation(summary = "下载文件")
    @PreAuthorize("@ss.hasPermi('file:file:download')")
    @GetMapping("/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            InputStream is = fileService.download(fileId, userId);
            StreamingResponseBody responseBody = outputStream -> {
                try (is) {
                    is.transferTo(outputStream);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            log.warn("文件下载未认证: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @Operation(summary = "下载指定版本文件")
    @PreAuthorize("@ss.hasPermi('file:file:download')")
    @GetMapping("/download/{fileId}/version/{version}")
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @PathVariable Long fileId,
            @PathVariable Integer version,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            InputStream is = fileService.downloadVersion(fileId, version, userId);
            StreamingResponseBody responseBody = outputStream -> {
                try (is) {
                    is.transferTo(outputStream);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("文件版本下载失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @Operation(summary = "获取文件版本历史")
    @PreAuthorize("@ss.hasPermi('file:file:history')")
    @GetMapping("/history/{fileId}")
    public Result<List<FileVersion>> history(@PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            if (!permissionService.hasAccess(userId, fileId, "READ")) {
                return Result.fail(403, "无权限查看版本历史");
            }
            List<com.cloudinfra.file.domain.FileVersion> domainVersions = fileService.getVersionHistory(fileId);
            List<FileVersion> dtoVersions = domainVersions.stream()
                .map(v -> {
                    FileVersion dto = new FileVersion();
                    dto.setId(v.getId());
                    dto.setFileId(v.getFileId());
                    dto.setVersionNumber(v.getVersionNumber());
                    dto.setStoragePath(v.getStoragePath());
                    dto.setCreateTime(v.getCreateTime());
                    return dto;
                })
                .collect(java.util.stream.Collectors.toList());
            return Result.success(dtoVersions);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("获取版本历史失败", e);
            return Result.fail(500, "获取版本历史失败");
        }
    }

    @Operation(summary = "根据关键词搜索文件")
    @PreAuthorize("@ss.hasPermi('file:file:search')")
    @GetMapping("/search")
    public Result<IPage<FileMetadataVO>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        if (keyword == null || keyword.isBlank()) {
            return Result.fail(400, "搜索关键词不能为空");
        }
        return Result.success(fileService.searchFiles(keyword.trim(), pageNum, pageSize));
    }
}