package com.cloudinfra.common.core.exception;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @GetMapping("/test/throw-base-exception")
    public void throwBase() {
        throw new BaseException(ErrorCode.USER_NOT_FOUND);
    }

    @GetMapping("/test/throw-generic-exception")
    public void throwGeneric() {
        throw new RuntimeException("Unexpected error");
    }
}
