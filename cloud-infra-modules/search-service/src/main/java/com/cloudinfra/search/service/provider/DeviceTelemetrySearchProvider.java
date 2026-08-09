package com.cloudinfra.search.service.provider;

import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
import com.cloudinfra.search.service.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DeviceTelemetrySearchProvider implements SearchProvider {

    private final DeviceTelemetryRepository telemetryRepository;

    @Override
    public List<UnifiedSearchResult> search(String query, int limit) {
        return telemetryRepository.findByKeyword(query, PageRequest.of(0, limit))
            .getContent()
            .stream()
            .map(doc -> new UnifiedSearchResult(
                doc.getId(),
                getResourceType(),
                "设备: " + doc.getDeviceId(),
                "设备ID: " + doc.getDeviceId(),
                1.0,
                doc.getTimestamp(),
                "/iot/devices/" + doc.getDeviceId()
            ))
            .collect(Collectors.toList());
    }

    @Override
    public String getResourceType() {
        return "DEVICE";
    }

    @Override
    public double getWeight() {
        return 1.2; // 设备搜索权重较高
    }
}