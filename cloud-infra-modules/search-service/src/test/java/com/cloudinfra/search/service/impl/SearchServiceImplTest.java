package com.cloudinfra.search.service.impl;

import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.domain.query.OperLogQuery;
import com.cloudinfra.search.domain.query.SearchQueryBuilder;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
import com.cloudinfra.search.repository.OperLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock private OperLogRepository operLogRepository;
    @Mock private DeviceTelemetryRepository telemetryRepository;
    @Mock private SearchQueryBuilder queryBuilder;

    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchServiceImpl(operLogRepository, telemetryRepository, queryBuilder);
    }

    @Test
    void searchOperLog_shouldReturnMappedResults() {
        // given
        SearchRequest request = new SearchRequest();
        request.setKeyword("test");
        request.setPageNum(1);
        request.setPageSize(10);

        OperLogDocument doc = new OperLogDocument();
        doc.setOperId(1L);
        doc.setTitle("Login");
        doc.setBusinessType(1);
        doc.setMethod("POST");
        doc.setOperName("admin");
        doc.setOperUrl("/api/login");
        doc.setOperIp("127.0.0.1");
        doc.setOperParam("{}");
        doc.setJsonResult("{\"success\":true}");
        doc.setStatus(1);
        doc.setErrorMsg(null);
        doc.setCreateTime(LocalDateTime.of(2025, 1, 1, 12, 0));

        Page<OperLogDocument> page = new PageImpl<>(List.of(doc));
        given(operLogRepository.findByKeyword(eq("test"), any(PageRequest.class))).willReturn(page);

        // when
        SearchResponse<OperLogDTO> response = searchService.searchOperLog(request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getTotal()).isEqualTo(1);

        OperLogDTO dto = response.getData().get(0);
        assertThat(dto.getOperId()).isEqualTo(1L);
        assertThat(dto.getTitle()).isEqualTo("Login");
        assertThat(dto.getOperName()).isEqualTo("admin");
        assertThat(dto.getMethod()).isEqualTo("POST");
        assertThat(dto.getOperUrl()).isEqualTo("/api/login");
        assertThat(dto.getOperTime()).isEqualTo(LocalDateTime.of(2025, 1, 1, 12, 0));
    }

    @Test
    void searchOperLog_whenEmpty_shouldReturnEmptyList() {
        // given
        SearchRequest request = new SearchRequest();
        request.setKeyword("nothing");
        request.setPageNum(1);
        request.setPageSize(10);

        Page<OperLogDocument> emptyPage = new PageImpl<>(List.of());
        given(operLogRepository.findByKeyword(eq("nothing"), any(PageRequest.class))).willReturn(emptyPage);

        // when
        SearchResponse<OperLogDTO> response = searchService.searchOperLog(request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getData()).isEmpty();
        assertThat(response.getTotal()).isZero();
    }

    @Test
    void searchOperLog_shouldUseDefaultQuery() {
        // given
        SearchRequest request = new SearchRequest();
        request.setKeyword("error");
        request.setPageNum(2);
        request.setPageSize(5);

        Page<OperLogDocument> page = new PageImpl<>(List.of(new OperLogDocument()));
        given(operLogRepository.findByKeyword(eq("error"), eq(PageRequest.of(1, 5)))).willReturn(page);

        // when
        SearchResponse<OperLogDTO> response = searchService.searchOperLog(request);

        // then
        assertThat(response).isNotNull();
        then(operLogRepository).should().findByKeyword(eq("error"), any(PageRequest.class));
    }
}
