package com.cloudinfra.api.file;

import com.cloudinfra.api.file.FileApi;
import com.cloudinfra.api.file.fallback.FileApiFallbackFactory;
import com.cloudinfra.api.file.dto.FileInfoResponse;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.api.file.dto.FileUploadRequest;
import com.cloudinfra.common.core.domain.Result;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@FeignClient(name = "file-service", fallbackFactory = FileApiFallbackFactory.class)
public interface FileApi {
    /**
     * 上传文件
     */
    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @CircuitBreaker(name = "fileService")
    @Retry(name = "fileService")
    @RateLimiter(name = "fileService")
    Result<FileInfoResponse> upload(@RequestPart("file") MultipartFile file, @RequestPart("metadata") FileUploadRequest metadata);

    /**
     * 根据ID获取文件详细信息
     */
    @GetMapping("/file/info/{id}")
    @CircuitBreaker(name = "fileService")
    @Retry(name = "fileService")
    Result<FileInfoResponse> getFileInfo(@PathVariable("id") Long id);

    /**
     * 根据关键词搜索文件
     */
    @GetMapping("/file/search")
    @CircuitBreaker(name = "fileService")
    Result<List<FileMetadataVO>> searchFiles(@RequestParam String keyword);
}