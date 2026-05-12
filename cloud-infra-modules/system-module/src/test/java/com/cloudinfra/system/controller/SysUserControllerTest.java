package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.test.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.cloud.nacos.config.import-check.enabled=false",
    "spring.main.allow-bean-definition-overriding=true"
})
public class SysUserControllerTest extends BaseIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserController sysUserController;

    @org.junit.jupiter.api.BeforeEach
    public void setup() {
        this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(sysUserController).build();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"admin"})
    public void testAddUser_InvalidInput_ReturnsParamError() throws Exception {
        // Create a SysUser with an empty username to trigger validation error
        SysUser user = new SysUser();
        user.setUsername(""); // This should trigger @NotBlank(message = "用户名不能为空")
        user.setPassword("password123");
        user.setEmail("test@example.com");

        String jsonUser = objectMapper.writeValueAsString(user);

        mockMvc.perform(post("/system/user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonUser))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("username: 用户名不能为空"));
    }
}
