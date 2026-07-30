package com.cloudinfra.search.domain;

import java.time.LocalDateTime;

/**
 * 统一搜索结果记录
 * 将不同来源的资源标准化为统一格式
 */
public record UnifiedSearchResult(
    String resourceId,      // 资源唯一ID
    String resourceType,    // 资源类型 (例如: "FILE", "NOTIFICATION", "USER", "LOG")
    String title,           // 显示标题
    String snippet,         // 内容摘要/片段
    Double score,           // 相关性评分
    LocalDateTime timestamp,// 资源创建/更新时间
    String url             // 跳转至该资源的链接/路径
) {}