package com.cloudinfra.system.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/dept")
public class SysDeptController {

    @PreAuthorize("@ss.hasPermission('system:dept:list')")
    @GetMapping("/list")
    public String list(Long parentId) {
        // TODO: Implement dept service logic
        return "List departments for parentId: " + parentId;
    }
}
