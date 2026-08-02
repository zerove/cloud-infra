package com.cloudinfra.common.core.exception;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 错误码管理端点
 * 提供错误码查询和文档下载
 */
@RestController
@RequestMapping("/api/error-codes")
public class ErrorCodeController {

    private final ErrorCodeRegistry registry;

    public ErrorCodeController(ErrorCodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * 查询全部错误码
     */
    @GetMapping
    public Map<Integer, ErrorCodeRegistry.ErrorCodeEntry> getAll() {
        return registry.getAll();
    }

    /**
     * 按模块查询错误码
     */
    @GetMapping("/module/{module}")
    public Map<Integer, ErrorCodeRegistry.ErrorCodeEntry> getByModule(@PathVariable String module) {
        return registry.getByModule(module);
    }

    /**
     * 查询单个错误码
     */
    @GetMapping("/{code}")
    public ErrorCodeRegistry.ErrorCodeEntry getByCode(@PathVariable int code) {
        return registry.get(code);
    }

    /**
     * 下载 Markdown 错误码文档
     */
    @GetMapping("/document")
    public String getDocument() {
        return registry.generateMarkdownDocument();
    }
}