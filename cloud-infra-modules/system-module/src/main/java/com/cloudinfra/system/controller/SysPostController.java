package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.SysPost;
import com.cloudinfra.system.service.ISysPostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/post")
public class SysPostController {

    @Autowired
    private ISysPostService postService;

    @GetMapping("/list")
    public ResponseEntity<List<SysPost>> list() {
        return ResponseEntity.ok(postService.list());
    }

    @PostMapping
    public ResponseEntity<String> add(@RequestBody SysPost post) {
        postService.save(post);
        return ResponseEntity.ok("Success");
    }
}
