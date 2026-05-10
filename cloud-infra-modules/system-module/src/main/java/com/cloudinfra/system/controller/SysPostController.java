package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.service.ISysPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "岗位管理")
@RestController
@RequestMapping("/system/post")
@RequiredArgsConstructor
public class SysPostController {

    private final ISysPostService postService;

    @Operation(summary = "查询岗位列表")
    @PreAuthorize("@ss.hasPermi('system:post:list')")
    @GetMapping("/list")
    public Result<List<SysPost>> list(SysPost post) {
        List<SysPost> list = postService.selectPostList(post);
        return Result.success(list);
    }

    @Operation(summary = "新增岗位")
    @PreAuthorize("@ss.hasPermi('system:post:add')")
    @Log(title = "岗位管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysPost post) {
        return postService.save(post) ? Result.success() : Result.error("新增岗位失败");
    }

    @Operation(summary = "修改岗位")
    @PreAuthorize("@ss.hasPermi('system:post:edit')")
    @Log(title = "岗位管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysPost post) {
        return postService.updateById(post) ? Result.success() : Result.error("修改岗位失败");
    }

    @Operation(summary = "删除岗位")
    @PreAuthorize("@ss.hasPermi('system:post:remove')")
    @Log(title = "岗位管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{postIds}")
    public Result<Void> remove(@PathVariable List<Long> postIds) {
        return postService.removeByIds(postIds) ? Result.success() : Result.error("删除岗位失败");
    }
}
