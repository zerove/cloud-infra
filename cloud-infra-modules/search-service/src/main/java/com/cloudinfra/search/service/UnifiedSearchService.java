package com.cloudinfra.search.service;

import com.cloudinfra.search.domain.UnifiedSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 统一搜索协调服务 - 实现多源联邦搜索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedSearchService {

    private final List<SearchProvider> providers;

    /**
     * 执行统一联邦搜索
     * @param query 搜索关键词
     * @param limit 最终结果集最大数量
     * @return 综合排序后的统一搜索结果
     */
    public List<UnifiedSearchResult> unifiedSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 使用虚拟线程并发请求所有 Provider
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<UnifiedSearchResult>>> futures = providers.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> {
                    try {
                        // 每个 provider 预取 limit 条结果
                        return provider.search(query, limit);
                    } catch (Exception e) {
                        log.error("Provider {} 搜索异常: {}", provider.getResourceType(), e.getMessage());
                        return Collections.<UnifiedSearchResult>emptyList();
                    }
                }, executor))
                .collect(Collectors.toList());

            // 等待所有完成（此时 join 不会阻塞虚拟线程，因为已经在虚拟线程执行器中）
            // 但为了最佳实践，改用 thenApply 链式处理
            CompletableFuture<List<UnifiedSearchResult>> allResultsFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                    .flatMap(f -> f.join().stream()) // allOf 完成后 join 不会阻塞
                    .map(result -> {
                        // 根据 Provider 的权重重新计算最终分数
                        SearchProvider provider = providers.stream()
                            .filter(p -> p.getResourceType().equals(result.resourceType()))
                            .findFirst()
                            .orElse(null);
                        
                        double weight = (provider != null) ? provider.getWeight() : 1.0;
                        return new UnifiedSearchResult(
                            result.resourceId(),
                            result.resourceType(),
                            result.title(),
                            result.snippet(),
                            result.score() * weight,
                            result.timestamp(),
                            result.url()
                        );
                    })
                    .sorted(Comparator.comparing(UnifiedSearchResult::score).reversed())
                    .limit(limit)
                    .collect(Collectors.toList()));

            return allResultsFuture.join(); // 最终 join 在虚拟线程中可接受
        } catch (Exception e) {
            log.error("统一搜索编排异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}