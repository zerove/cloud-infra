package com.cloudinfra.system.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.service.ISysPostService;

@RestController
@RequestMapping("/system/post")
public class SysPostController {

    @Autowired
    private ISysPostService postService;

    @PreAuthorize("@ss.hasPermission('system:post:query')")
    @GetMapping("/list")
    public Result<List<SysPost>> list() {
        return Result.success(postService.list());
    }

    @PreAuthorize("@ss.hasPermission('system:post:query')")
    @GetMapping("/{postId}")
    public Result<SysPost> getInfo(@PathVariable Long postId) {
        return Result.success(postService.getById(postId));
    }

    @PreAuthorize("@ss.hasPermission('system:post:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysPost post) {
        postService.save(post);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:post:edit')")
    @PutMapping
    public Result<Void> edit(@RequestBody SysPost post) {
        postService.updateById(post);
        return Result.success();
    }

    @PreAuthorize("@ss.hasPermission('system:post:remove')")
    @DeleteMapping("/{postIds}")
    public Result<Void> remove(@PathVariable List<Long> postIds) {
        postService.deletePostByIds(postIds);
        return Result.success();
    }
}
