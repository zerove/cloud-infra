package com.cloudinfra.search.service;

import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.search.domain.DeviceTelemetryDocument;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索服务接口
 * 提供对操作日志等数据的全文搜索功能
 */
public interface SearchService {

    /**
     * 查询设备的遥测历史。
     *
     * <p>{@code getTelemetryHistory} 原先由 {@code SearchController} 直接注入
     * {@code DeviceTelemetryRepository} 调用 —— 控制器绕过 service 层直达 repository。
     */
    List<DeviceTelemetryDocument> findTelemetryHistory(String deviceId, LocalDateTime start, LocalDateTime end);
    /**
     * 分页搜索操作日志
     *
     * @param request 搜索请求，包含关键词、页码和页大小
     * @return 包含搜索结果及总条数的响应对象
     */
    SearchResponse<OperLogDTO> searchOperLog(SearchRequest request);
}
