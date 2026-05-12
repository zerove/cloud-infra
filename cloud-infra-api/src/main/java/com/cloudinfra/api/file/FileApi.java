package com.cloudinfra.api.file;

import com.cloudinfra.api.file.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 文件服务 API 接口
 * 提供文件的上传与信息查询功能
 */
@FeignClient(name = "file-service")
public interface FileApi {

    /**
     * 上传文件
     *
     * @param request 文件上传请求参数
     * @return 文件信息响应结果
     */
    @PostMapping("/file/upload")
    Result<FileInfoResponse> upload(@RequestBody FileUploadRequest request);

    /**
     * 根据ID获取文件详细信息
     *
     * @param id 文件唯一标识ID
     * @return 文件信息响应结果
     */
    @GetMapping("/file/sys/file/{id}")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);
}
