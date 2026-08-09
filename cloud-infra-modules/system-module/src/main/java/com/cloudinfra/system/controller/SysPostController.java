package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.exception.SystemException;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.service.ISysPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public List<SysPost> list(SysPost post) {
        return postService.selectPostList(post);
    }

    @Operation(summary = "新增岗位")
    @PreAuthorize("@ss.hasPermi('system:post:add')")
    @AuditLog(module = "POST", action = "ADD")
    @PostMapping
    public Result<Void> add(@RequestBody SysPost post) {
        if (!postService.save(post)) {
            throw new SystemException("新增岗位失败");
        }
        return Result.success();
    }

    @Operation(summary = "修改岗位")
    @PreAuthorize("@ss.hasPermi('system:post:edit')")
    @AuditLog(module = "POST", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@RequestBody SysPost post) {
        if (!postService.updateById(post)) {
            throw new SystemException("修改岗位失败");
        }
        return Result.success();
    }

    @Operation(summary = "删除岗位")
    @PreAuthorize("@ss.hasPermi('system:post:remove')")
    @AuditLog(module = "POST", action = "DELETE")
    @DeleteMapping("/{postIds}")
    public Result<Void> remove(@PathVariable List<Long> postIds) {
        if (!postService.removeByIds(postIds)) {
            throw new SystemException("删除岗位失败");
        }
        return Result.success();
    }
}