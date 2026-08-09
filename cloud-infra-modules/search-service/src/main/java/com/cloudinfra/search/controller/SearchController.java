package com.cloudinfra.search.controller;

import com.cloudinfra.api.search.SearchApi;
import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.repository.DeviceTelemetryRepository;
import com.cloudinfra.search.service.SearchService;
import com.cloudinfra.search.service.UnifiedSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索管理控制器
 * 提供对操作日志、IoT 遥测数据以及全平台统一搜索的接口实现
 */
@Tag(name = "搜索管理")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController implements SearchApi {

    private final SearchService searchService;
    private final DeviceTelemetryRepository telemetryRepository;
    private final UnifiedSearchService unifiedSearchService;

    /**
     * 统一联邦搜索接口 - 全平台资源一键检索
     */
    @Operation(summary = "统一联邦搜索")
    @GetMapping("/unified")
    public Result<List<UnifiedSearchResult>> unifiedSearch(
            @RequestParam(value = "q", required = true) String q,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(unifiedSearchService.unifiedSearch(q, limit));
    }

    /**
     * 搜索操作日志
     */
    @Override
    @Operation(summary = "搜索操作日志")
    @GetMapping("/operlog")
    public Result<SearchResponse<OperLogDTO>> searchOperLog(
        SearchRequest request
    ) {
        return Result.success(searchService.searchOperLog(request));
    }

    /**
     * 查询设备遥测历史
     */
    @Operation(summary = "查询设备遥测历史")
    @GetMapping("/iot/telemetry/{deviceId}")
    public Result<List<DeviceTelemetryDocument>> getTelemetryHistory(
            @PathVariable String deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        
        List<DeviceTelemetryDocument> history = telemetryRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end);
        return Result.success(history);
    }
}