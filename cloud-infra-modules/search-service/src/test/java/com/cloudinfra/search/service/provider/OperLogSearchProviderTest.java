package com.cloudinfra.search.service.provider;

import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.repository.OperLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OperLogSearchProviderTest {

    @Mock
    private OperLogRepository operLogRepository;

    private OperLogSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OperLogSearchProvider(operLogRepository);
    }

    @Test
    void search_shouldMapDocumentsToResults() {
        OperLogDocument doc = new OperLogDocument();
        doc.setOperId(1L);
        doc.setTitle("用户管理-创建用户");
        doc.setOperName("管理员");
        doc.setMethod("POST");
        doc.setCreateTime(LocalDateTime.of(2026, 8, 17, 10, 0));
        given(operLogRepository.findByKeyword("用户", PageRequest.of(0, 10)))
                .willReturn(new PageImpl<>(List.of(doc)));

        List<UnifiedSearchResult> results = provider.search("用户", 10);

        assertThat(results).hasSize(1);
        UnifiedSearchResult r = results.get(0);
        assertThat(r.resourceId()).isEqualTo("1");
        assertThat(r.resourceType()).isEqualTo("OPER_LOG");
        assertThat(r.title()).isEqualTo("用户管理-创建用户");
        assertThat(r.snippet()).isEqualTo("管理员 - POST");
        assertThat(r.timestamp()).isEqualTo(LocalDateTime.of(2026, 8, 17, 10, 0));
        assertThat(r.url()).isEqualTo("/system/logs/1");
    }

    @Test
    void search_whenEmptyPage_shouldReturnEmptyList() {
        given(operLogRepository.findByKeyword("x", PageRequest.of(0, 5)))
                .willReturn(new PageImpl<>(List.of()));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void resourceTypeAndWeight_shouldBeOperLog() {
        assertThat(provider.getResourceType()).isEqualTo("OPER_LOG");
        assertThat(provider.getWeight()).isEqualTo(0.8);
    }
}
