package com.cloudinfra.search.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;
import com.cloudinfra.search.service.IndexAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ES 索引管理控制器
 * 提供索引列表、详情、重建与删除等运维能力
 */
@Tag(name = "ES 索引管理")
@RestController
@RequestMapping("/search/admin")
@RequiredArgsConstructor
public class IndexAdminController {

    private final IndexAdminService indexAdminService;

    /**
     * 列出全部索引
     */
    @Operation(summary = "列出全部 ES 索引")
    @PreAuthorize("@ss.hasPermi('search:index:list')")
    @GetMapping("/indexes")
    public Result<List<IndexInfoVO>> listIndexes() {
        return Result.success(indexAdminService.listIndexes());
    }

    /**
     * 查询索引详情(mapping、settings、文档数)
     */
    @Operation(summary = "查询索引详情")
    @PreAuthorize("@ss.hasPermi('search:index:detail')")
    @GetMapping("/indexes/{indexName}")
    public Result<IndexDetailVO> getIndexDetail(@PathVariable String indexName) {
        return Result.success(indexAdminService.getIndexDetail(indexName));
    }

    /**
     * 重建索引: 清空并按 @Document 定义重建(仅支持平台已知业务索引)
     */
    @Operation(summary = "重建索引(清空数据)")
    @PreAuthorize("@ss.hasPermi('search:index:rebuild')")
    @PostMapping("/indexes/{indexName}/rebuild")
    public Result<Void> rebuildIndex(@PathVariable String indexName) {
        indexAdminService.rebuildIndex(indexName);
        return Result.success();
    }

    /**
     * 删除索引
     */
    @Operation(summary = "删除索引")
    @PreAuthorize("@ss.hasPermi('search:index:delete')")
    @DeleteMapping("/indexes/{indexName}")
    public Result<Void> deleteIndex(@PathVariable String indexName) {
        indexAdminService.deleteIndex(indexName);
        return Result.success();
    }
}
