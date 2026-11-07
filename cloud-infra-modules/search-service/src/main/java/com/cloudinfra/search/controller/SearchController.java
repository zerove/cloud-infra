package com.cloudinfra.search.controller;

import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.service.SearchService;
import com.cloudinfra.search.service.UnifiedSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final UnifiedSearchService unifiedSearchService;

    /**
     * 统一联邦搜索接口 - 全平台资源一键检索
     */
    @Operation(summary = "统一联邦搜索")
    @PreAuthorize("@ss.hasPermi('search:unified')")
    @GetMapping("/unified")
    public Result<List<UnifiedSearchResult>> unifiedSearch(
            @RequestParam(value = "q", required = true) @NotBlank(message = "搜索关键字不能为空") String q,
            @RequestParam(value = "limit", defaultValue = "20")
            @Min(value = 1, message = "limit 必须大于等于 1")
            @Max(value = 100, message = "limit 不能超过 100") int limit) {
        return Result.success(unifiedSearchService.unifiedSearch(q, limit));
    }

    /**
     * 搜索操作日志
     */
    @Operation(summary = "搜索操作日志")
    @PreAuthorize("@ss.hasPermi('search:operlog')")
    @GetMapping("/operlog")
    public Result<SearchResponse<OperLogDTO>> searchOperLog(
        @Valid SearchRequest request
    ) {
        return Result.success(searchService.searchOperLog(request));
    }

    /**
     * 查询设备遥测历史
     */
    @Operation(summary = "查询设备遥测历史")
    @PreAuthorize("@ss.hasPermi('search:telemetry')")
    @GetMapping("/iot/telemetry/{deviceId}")
    public Result<List<DeviceTelemetryDocument>> getTelemetryHistory(
            @PathVariable String deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        
        List<DeviceTelemetryDocument> history = searchService.findTelemetryHistory(deviceId, start, end);
        return Result.success(history);
    }
}