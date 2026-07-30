package com.cloudinfra.search.service.provider;

import com.cloudinfra.api.file.FileApi;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.service.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FileSearchProvider implements SearchProvider {

    private final FileApi fileApi;

    @Override
    public List<UnifiedSearchResult> search(String query, int limit) {
        try {
            Result<List<FileMetadataVO>> result = fileApi.searchFiles(query);
            if (result == null || result.getData() == null) {
                return Collections.emptyList();
            }

            return result.getData().stream()
                .limit(limit)
                .map(vo -> new UnifiedSearchResult(
                    vo.fileId().toString(),
                    getResourceType(),
                    vo.fileName(),
                    "文件类型: " + vo.contentType(),
                    1.0,
                    null, // FileMetadataVO currently doesn't have timestamp
                    "/file/download/" + vo.fileId()
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public String getResourceType() {
        return "FILE";
    }

    @Override
    public double getWeight() {
        return 1.5; // 文件搜索权重较高
    }
}