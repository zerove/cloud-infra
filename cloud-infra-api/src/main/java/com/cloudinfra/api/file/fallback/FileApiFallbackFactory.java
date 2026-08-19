package com.cloudinfra.api.file.fallback;

import com.cloudinfra.api.file.FileApi;
import com.cloudinfra.api.file.dto.FileInfoResponse;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.api.file.dto.FileUploadRequest;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

/**
 * FileApi 降级工厂
 */
@Component
public class FileApiFallbackFactory implements FallbackFactory<FileApi> {

    private static final Logger log = LoggerFactory.getLogger(FileApiFallbackFactory.class);

    @Override
    public FileApi create(Throwable cause) {
        log.warn("FileApi fallback triggered: {}", cause.getMessage());
        return new FileApi() {
            @Override
            public Result<FileInfoResponse> upload(MultipartFile file, FileUploadRequest metadata) {
                return Result.fail(503, "文件服务暂不可用");
            }

            @Override
            public Result<FileInfoResponse> getFileInfo(Long id) {
                return Result.fail(503, "文件服务暂不可用");
            }

            @Override
            public Result<List<FileMetadataVO>> searchFiles(String keyword) {
                return Result.success(Collections.emptyList());
            }
        };
    }
}