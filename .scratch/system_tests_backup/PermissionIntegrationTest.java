package com.cloudinfra.system.security;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.cloudinfra.system.SystemTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PermissionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testMockMvcSetup() throws Exception {
        // 验证 MockMvc 环境
        mockMvc.perform(delete("/system/1"))
               .andExpect(status().is4xxClientError()); // 或期望特定响应
    }
}
