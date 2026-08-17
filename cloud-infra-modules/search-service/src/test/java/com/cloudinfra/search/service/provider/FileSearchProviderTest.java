package com.cloudinfra.search.service.provider;

import com.cloudinfra.api.file.FileApi;
import com.cloudinfra.api.file.dto.FileMetadataVO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class FileSearchProviderTest {

    @Mock
    private FileApi fileApi;

    private FileSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FileSearchProvider(fileApi);
    }

    @Test
    void search_shouldMapFilesToResults() {
        FileMetadataVO vo = new FileMetadataVO(
                100L, "报告.pdf", "/data/report.pdf", "/file/download/100", 2048L,
                "application/pdf", null);
        given(fileApi.searchFiles("报告")).willReturn(Result.success(List.of(vo)));

        List<UnifiedSearchResult> results = provider.search("报告", 10);

        assertThat(results).hasSize(1);
        UnifiedSearchResult r = results.get(0);
        assertThat(r.resourceId()).isEqualTo("100");
        assertThat(r.resourceType()).isEqualTo("FILE");
        assertThat(r.title()).isEqualTo("报告.pdf");
        assertThat(r.snippet()).isEqualTo("文件类型: application/pdf");
        assertThat(r.url()).isEqualTo("/file/download/100");
    }

    @Test
    void search_shouldRespectLimit() {
        FileMetadataVO vo1 = new FileMetadataVO(1L, "a.pdf", "/a", "/f/1", 1L, "pdf", null);
        FileMetadataVO vo2 = new FileMetadataVO(2L, "b.pdf", "/b", "/f/2", 1L, "pdf", null);
        given(fileApi.searchFiles("pdf")).willReturn(Result.success(List.of(vo1, vo2)));

        List<UnifiedSearchResult> results = provider.search("pdf", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).resourceId()).isEqualTo("1");
    }

    @Test
    void search_whenResultNull_shouldReturnEmptyList() {
        given(fileApi.searchFiles("x")).willReturn(null);

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void search_whenDataNull_shouldReturnEmptyList() {
        given(fileApi.searchFiles("x")).willReturn(Result.success(null));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void search_whenApiThrows_shouldReturnEmptyList() {
        given(fileApi.searchFiles("x")).willThrow(new RuntimeException("feign down"));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void resourceTypeAndWeight_shouldBeFile() {
        assertThat(provider.getResourceType()).isEqualTo("FILE");
        assertThat(provider.getWeight()).isEqualTo(1.5);
    }
}
