package com.cloudinfra.api.file;

import com.cloudinfra.api.file.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@FeignClient(name = "file-service")
public interface FileApi {
    /**
     * 上传文件
     */
    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<FileInfoResponse> upload(@RequestPart("file") MultipartFile file, @RequestPart("metadata") FileUploadRequest metadata);

    /**
     * 根据ID获取文件详细信息
     */
    @GetMapping("/file/info/{id}")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);

    /**
     * 根据关键词搜索文件
     */
    @GetMapping("/file/search")
    Result<List<FileMetadataVO>> searchFiles(@RequestParam String keyword);
}