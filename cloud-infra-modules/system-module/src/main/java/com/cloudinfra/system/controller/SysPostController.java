package com.cloudinfra.system.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/post")
public class SysPostController {

    @PreAuthorize("@ss.hasPermi('system:post:list')")
    @GetMapping("/list")
    public String list() {
        return "list";
    }

    @PreAuthorize("@ss.hasPermi('system:post:add')")
    @PostMapping
    public String add(@RequestBody String post) {
        return "add";
    }

    @PreAuthorize("@ss.hasPermi('system:post:edit')")
    @PostMapping("/{postId}/permissions")
    public String updatePermissions(@PathVariable Long postId, @RequestBody String permissionIds) {
        return "updatePermissions";
    }
}
