package com.cloudinfra.api.file;

import com.cloudinfra.api.file.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "file-service")
public interface FileApi {
    @PostMapping("/file/upload")
    Result<FileInfoResponse> upload(@RequestBody FileUploadRequest request);

    @GetMapping("/file/sys/file/{id}")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);
}
