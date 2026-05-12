package com.cloudinfra.api.file;

import com.cloudinfra.api.file.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务 API 接口
 * 提供文件的上传与信息查询功能
 */
@FeignClient(name = "file-service")
public interface FileApi {

    /**
     * 上传文件
     *
     * @param file 文件内容
     * @param metadata 文件上传请求参数
     * @return 文件信息响应结果
     */
    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<FileInfoResponse> upload(@RequestPart("file") MultipartFile file, @RequestPart("metadata") FileUploadRequest metadata);

    /**
     * 根据ID获取文件详细信息
     *
     * @param id 文件唯一标识ID
     * @return 文件信息响应结果
     */
    @GetMapping("/file/info/{id}")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);
}
