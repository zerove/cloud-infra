package com.cloudinfra.search.service.provider;

import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
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
class DeviceTelemetrySearchProviderTest {

    @Mock
    private DeviceTelemetryRepository telemetryRepository;

    private DeviceTelemetrySearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DeviceTelemetrySearchProvider(telemetryRepository);
    }

    @Test
    void search_shouldMapDocumentsToResults() {
        DeviceTelemetryDocument doc = new DeviceTelemetryDocument();
        doc.setId("tel-1");
        doc.setDeviceId("dev-42");
        doc.setTimestamp(LocalDateTime.of(2026, 8, 17, 11, 30));
        given(telemetryRepository.findByKeyword("dev", PageRequest.of(0, 10)))
                .willReturn(new PageImpl<>(List.of(doc)));

        List<UnifiedSearchResult> results = provider.search("dev", 10);

        assertThat(results).hasSize(1);
        UnifiedSearchResult r = results.get(0);
        assertThat(r.resourceId()).isEqualTo("tel-1");
        assertThat(r.resourceType()).isEqualTo("DEVICE");
        assertThat(r.title()).isEqualTo("设备: dev-42");
        assertThat(r.snippet()).isEqualTo("设备ID: dev-42");
        assertThat(r.timestamp()).isEqualTo(LocalDateTime.of(2026, 8, 17, 11, 30));
        assertThat(r.url()).isEqualTo("/iot/devices/dev-42");
    }

    @Test
    void search_whenEmptyPage_shouldReturnEmptyList() {
        given(telemetryRepository.findByKeyword("x", PageRequest.of(0, 5)))
                .willReturn(new PageImpl<>(List.of()));

        assertThat(provider.search("x", 5)).isEmpty();
    }

    @Test
    void resourceTypeAndWeight_shouldBeDevice() {
        assertThat(provider.getResourceType()).isEqualTo("DEVICE");
        assertThat(provider.getWeight()).isEqualTo(1.2);
    }
}
