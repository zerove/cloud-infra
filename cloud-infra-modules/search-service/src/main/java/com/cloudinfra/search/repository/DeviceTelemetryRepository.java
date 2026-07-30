package com.cloudinfra.search.repository;

import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceTelemetryRepository extends ElasticsearchRepository<DeviceTelemetryDocument, String> {
    List<DeviceTelemetryDocument> findByDeviceIdAndTimestampBetween(String deviceId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    @Query("{\"bool\": {\"must\": [{\"multi_match\": {\"query\": \"?0\", \"fields\": [\"deviceId\", \"deviceName\", \"tags\"]}}]}}")
    Page<DeviceTelemetryDocument> findByKeyword(String keyword, Pageable pageable);
}