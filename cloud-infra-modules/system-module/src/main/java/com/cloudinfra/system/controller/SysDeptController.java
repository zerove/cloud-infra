package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/dept")
public class SysDeptController {

    @Autowired
    private ISysDeptService deptService;

    @GetMapping("/list")
    public ResponseEntity<List<SysDept>> list(@RequestParam(required = false) Long parentId) {
        return ResponseEntity.ok(deptService.selectDeptList(parentId));
    }

    @PostMapping
    public ResponseEntity<String> add(@RequestBody SysDept dept) {
        deptService.insertDept(dept);
        return ResponseEntity.ok("Success");
    }
}
