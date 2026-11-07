package com.cloudinfra.file.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.api.file.dto.FileVersion;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.file.domain.FileContent;
import com.cloudinfra.file.service.FileStorageUrlService;
import com.cloudinfra.file.service.IFileService;
import com.cloudinfra.file.service.FilePermissionService;
import com.cloudinfra.file.util.FileValidator;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "文件管理")
@RestController
@RequestMapping("/file")
@Validated
@RequiredArgsConstructor
public class FileController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileController.class);
    private final IFileService fileService;
    private final FileValidator fileValidator;
    private final FilePermissionService permissionService;
    private final FileStorageUrlService storageUrlService;

    /**
     * 申请上传预签名 URL (前端直传 MinIO, 不经过 file-service 中转)
     *
     * <p>典型流程:
     * <ol>
     *   <li>前端 POST /file/presign-upload, body: {filename, contentType, size}
     *   <li>后端校验权限 + 配额, 返回 {objectKey, uploadUrl, expiresAt}
     *   <li>前端 PUT uploadUrl (直接对 MinIO, 不经过 file-service)
     *   <li>前端 POST /file/commit-upload 确认 (后续扩展)
     * </ol>
     * <p>权限: file:file:upload. 文件名 server-side 拼 objectKey, 防路径穿越.</p>
     */
    @Operation(summary = "申请上传预签名 URL (前端直传 MinIO)")
    @PreAuthorize("@ss.hasPermi('file:file:upload')")
    @PostMapping("/presign-upload")
    public Result<Map<String, String>> presignUpload(
            @RequestParam("filename") @NotBlank(message = "文件名不能为空") String filename,
            @RequestParam("contentType") @NotBlank(message = "文件类型不能为空") String contentType,
            @RequestParam(value = "size", required = false) @Positive(message = "文件大小必须为正数") Long size,
            @RequestAttribute(value = "userId", required = false) Long requestUserId)
            throws ServerException, InsufficientDataException, ErrorResponseException, IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException,
            InternalException {
        Long userId = resolveUserId(requestUserId);
        fileValidator.validate(filename, contentType, size);

        // 构造 objectKey: userId/uuid-filename (UUID 防碰撞 + 防目录穿越)
        String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = userId + "/" + java.util.UUID.randomUUID() + "-" + safeName;

        // 5 分钟过期
        int expirySeconds = 300;
        String uploadUrl = storageUrlService.presignPut(objectKey, expirySeconds);

        Map<String, String> result = new HashMap<>();
        result.put("objectKey", objectKey);
        result.put("uploadUrl", uploadUrl);
        result.put("contentType", contentType);
        long expiresAt = System.currentTimeMillis() / 1000 + expirySeconds;
        result.put("expiresAt", String.valueOf(expiresAt));
        return Result.success(result);
    }

    /**
     * 申请下载预签名 URL (前端直连 MinIO, 不经后端流式)
     *
     * <p>权限: file:file:download. 对象名由后端根据 fileId 查表, 客户端不传防越权.</p>
     */
    @Operation(summary = "申请下载预签名 URL (前端直连 MinIO)")
    @PreAuthorize("@ss.hasPermi('file:file:download')")
    @GetMapping("/presign-download/{fileId}")
    public Result<Map<String, String>> presignDownload(
            @PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId)
            throws ServerException, InsufficientDataException, ErrorResponseException, IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException,
            InternalException {
        Long userId = resolveUserId(requestUserId);

        // 查表获取 objectKey + 权限校验 (复用现有 download 逻辑)
        String objectKey = fileService.getObjectKeyForDownload(fileId, userId);

        // 10 分钟过期
        int expirySeconds = 600;
        String downloadUrl = storageUrlService.presignGet(objectKey, expirySeconds);

        Map<String, String> result = new HashMap<>();
        result.put("fileId", String.valueOf(fileId));
        result.put("downloadUrl", downloadUrl);
        long expiresAt = System.currentTimeMillis() / 1000 + expirySeconds;
        result.put("expiresAt", String.valueOf(expiresAt));
        return Result.success(result);
    }

    /**
     * 大文件分片上传 - 申请分片预签名 URL
     *
     * <p>典型流程:
     * <ol>
     *   <li>前端 POST /file/presign-multipart-init, body: {filename, totalSize, partCount}
     *   <li>后端生成 uploadId + 多个 partUploadUrls
     *   <li>前端并行 PUT 每个分片
     *   <li>前端 POST /file/presign-multipart-complete 触发 MinIO completeMultipartUpload
     * </ol>
     * <p>权限: file:file:upload. 单分片 5 分钟过期.</p>
     */
    @Operation(summary = "大文件分片上传 - 初始化 + 申请分片 URL")
    @PreAuthorize("@ss.hasPermi('file:file:upload')")
    @PostMapping("/presign-multipart-init")
    public Result<Map<String, Object>> presignMultipartInit(
            @RequestParam("filename") @NotBlank(message = "文件名不能为空") String filename,
            @RequestParam("contentType") @NotBlank(message = "文件类型不能为空") String contentType,
            @RequestParam("totalSize") @Positive(message = "文件大小必须为正数") Long totalSize,
            @RequestParam("partCount")
            @Min(value = 1, message = "分片数必须大于等于 1")
            @Max(value = 1000, message = "分片数不能超过 1000") int partCount,
            @RequestAttribute(value = "userId", required = false) Long requestUserId)
            throws ServerException, InsufficientDataException, ErrorResponseException, IOException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException,
            InternalException {
        Long userId = resolveUserId(requestUserId);
        fileValidator.validate(filename, contentType, totalSize);

        String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = userId + "/multipart-" + java.util.UUID.randomUUID() + "-" + safeName;

        // MinIO 8.5.x 不暴露 initiateMultipartUpload API, 这里使用 S3 SDK 直接 multipart upload
        // 协议: 前端用 s3 multipart (CreateMultipartUpload/UploadPart/CompleteMultipartUpload)
        // 简化: 仅生成 part URLs, uploadId 由前端在 CreateMultipartUpload 后回填
        // (如需更细粒度控制, 升级到 minio 8.6+ 或换用 AWS S3 SDK)
        String uploadId = java.util.UUID.randomUUID().toString();

        // 2. 为每个分片生成预签名 URL
        int partExpiry = 300;
        Map<String, String> partUrls = new HashMap<>();
        for (int i = 1; i <= partCount; i++) {
            String partUrl = storageUrlService.presignPut(objectKey, partExpiry);
            partUrls.put("part" + i, partUrl);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("objectKey", objectKey);
        result.put("uploadId", uploadId);
        result.put("partUrls", partUrls);
        result.put("totalParts", partCount);
        result.put("note", "S3 multipart: 前端发起 CreateMultipartUpload 后, 用 partUrls PUT 每个分片, 最后 CompleteMultipartUpload");
        long expiresAt = System.currentTimeMillis() / 1000 + partExpiry;
        result.put("expiresAt", expiresAt);
        return Result.success(result);
    }

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
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        try {
            Long userId = resolveUserId(requestUserId);
            if (!permissionService.hasAccess(userId, fileId, "READ")) {
                return Result.fail(403, "无权限查看版本历史");
            }
            List<com.cloudinfra.file.domain.FileVersion> domainVersions = fileService.getVersionHistory(
                    fileId, Math.max(page, 1), Math.min(Math.max(size, 1), 100));
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

    @Operation(summary = "删除文件（硬删除，不可恢复）")
    @PreAuthorize("@ss.hasPermi('file:file:remove')")
    @DeleteMapping("/{fileId}")
    public Result<Void> remove(
            @PathVariable Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        Long userId = resolveUserId(requestUserId);
        // service 层抛 BusinessException 时由 GlobalExceptionHandler 统一转 Result,
        // 但其 getSuggestedHttpStatus() 恒为 500 —— 权限/不存在这类语义失败
        // 不该被读成"服务故障"。故在此显式映射, 保持与 @PreAuthorize 的 401/403 一致的语义层。
        try {
            fileService.delete(fileId, userId);
            return Result.success();
        } catch (BusinessException e) {
            // 按 ErrorCode 枚举比对, 不用裸数字: 1005/1006 一旦错误码调整,
            // 裸数字会静默失配并退化成 500(第四课: 判据必须指向被检对象本身)
            if (ErrorCode.NO_PERMISSION.getCode().equals(e.getCode())) {
                return Result.fail(403, e.getMessage());
            }
            if (ErrorCode.RESOURCE_NOT_FOUND.getCode().equals(e.getCode())) {
                return Result.fail(404, e.getMessage());
            }
            // 其余(含存储删除失败 SYSTEM_ERROR): 保留可读原因, 不吞掉
            log.error("文件删除失败 [fileId={}]: {}", fileId, e.getMessage());
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "根据关键词搜索文件")
    @PreAuthorize("@ss.hasPermi('file:file:search')")
    @GetMapping("/search")
    public Result<IPage<FileMetadataVO>> search(
            @RequestParam @NotBlank(message = "搜索关键词不能为空") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于等于 1") long pageNum,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页大小必须大于等于 1")
            @Max(value = 100, message = "每页大小不能超过 100") long pageSize) {
        return Result.success(fileService.searchFiles(keyword.trim(), pageNum, pageSize));
    }

    @Operation(summary = "获取文件元数据")
    @PreAuthorize("@ss.hasPermi('file:file:download')")
    @GetMapping("/metadata/{fileId}")
    public Result<FileMetadataVO> getMetadata(
            @PathVariable @Positive(message = "文件 ID 必须为正数") Long fileId,
            @RequestAttribute(value = "userId", required = false) Long requestUserId) {
        Long userId = resolveUserId(requestUserId);
        return Result.success(fileService.getMetadata(fileId, userId));
    }
}
