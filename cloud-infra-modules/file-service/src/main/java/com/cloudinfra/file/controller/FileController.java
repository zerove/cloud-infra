package com.cloudinfra.file.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
    private final FilePermissionService permissionService;

    @Operation(summary = "上传文件")
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
            return Result.fail(500, "文件上传失败: " + e.getMessage());
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

    @Operation(summary = "下载文件")
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            InputStream is = fileService.download(fileId, userId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(is));
        } catch (IllegalArgumentException e) {
            log.warn("文件下载未认证: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @Operation(summary = "更新文件（创建新版本）")
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
            return Result.fail(500, "文件更新失败: " + e.getMessage());
        }
    }

    @Operation(summary = "下载指定版本文件")
    @GetMapping("/download/{fileId}/version/{version}")
    public ResponseEntity<Resource> downloadVersion(
            @PathVariable Long fileId,
            @PathVariable Integer version,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            InputStream is = fileService.downloadVersion(fileId, version, userId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(is));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("文件版本下载失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @Operation(summary = "获取文件版本历史")
    @GetMapping("/history/{fileId}")
    public Result<java.util.List<com.cloudinfra.file.domain.FileVersion>> history(
            @PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            // 读取版本历史需 READ 权限
            if (!permissionService.hasAccess(userId, fileId, "READ")) {
                return Result.fail(403, "无权查看该文件的版本历史");
            }
            return Result.success(fileService.getVersionHistory(fileId));
        } catch (IllegalArgumentException e) {
            return Result.fail(401, e.getMessage());
        }
    }

    @Operation(summary = "删除文件")
    @DeleteMapping("/delete/{fileId}")
    public Result<Void> delete(
            @PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            // 权限校验在服务层完成（含上传者 OWNER 放行）
            if (fileService.delete(fileId, userId)) {
                return Result.success();
            }
            return Result.fail(500, "文件删除失败");
        } catch (IllegalArgumentException e) {
            return Result.fail(401, e.getMessage());
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage());
            return Result.fail(403, "无权删除该文件");
        }
    }

    @Operation(summary = "根据关键词搜索文件")
    @GetMapping("/search")
    public Result<java.util.List<FileMetadataVO>> search(@RequestParam String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Result.fail(400, "搜索关键词不能为空");
        }
        return Result.success(fileService.searchFiles(keyword.trim()));
    }
}