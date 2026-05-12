package com.cloudinfra.common.core.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        TestController controller = new TestController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(handler)
            .build();
    }

    @Test
    public void testBaseExceptionHandling() throws Exception {
        // 模拟一个触发 BaseException 的请求
        mockMvc
            .perform(get("/test/throw-base-exception"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(2001)) // USER_NOT_FOUND
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    public void testGenericExceptionHandling() throws Exception {
        mockMvc
            .perform(get("/test/throw-generic-exception"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(1000))
            .andExpect(jsonPath("$.message").value("系统内部错误，请稍后重试"));
    }
}
