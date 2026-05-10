package com.cloudinfra.search.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索服务接口
 */
@Tag(name = "搜索管理")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final OperLogRepository operLogRepository;

    @Operation(summary = "全文本搜索操作日志")
    @GetMapping("/operlog")
    public Result<List<OperLogDocument>> searchOperLog(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        // 此处为简单实现，生产环境建议使用 NativeQuery 构建复杂检索（含高亮）
        Page<OperLogDocument> result = operLogRepository.findAll(PageRequest.of(page, size));
        return Result.success(result.getContent());
    }

    @Operation(summary = "保存操作日志索引")
    @PostMapping("/operlog")
    public Result<Void> save(@RequestBody OperLogDocument document) {
        operLogRepository.save(document);
        return Result.success();
    }
}
