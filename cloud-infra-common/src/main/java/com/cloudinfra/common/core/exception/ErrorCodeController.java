package com.cloudinfra.common.core.exception;

import org.springframework.security.access.prepost.PreAuthorize;
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
// 该控制器位于 cloud-infra-common, 会被每个应用的 scanBasePackages("com.cloudinfra") 扫入,
// 于是 /api/error-codes 此前在**全部服务**上无鉴权暴露。仓库规则是"非 /internal/ 端点必须
// 有 @PreAuthorize", 而 ControllerAuthorizationTest 只扫 com.cloudinfra.system, 看不见这里。
@PreAuthorize("hasAuthority('system:error-code:list')")
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