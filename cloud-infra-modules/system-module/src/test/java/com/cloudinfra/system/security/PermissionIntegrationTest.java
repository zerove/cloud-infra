package com.cloudinfra.system.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class PermissionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = {"system:user:remove"})
    void shouldAllowDeleteWhenPermissionGranted() throws Exception {
        mockMvc.perform(delete("/system/1"))
               .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"system:user:view"})
    void shouldDenyDeleteWhenPermissionNotGranted() throws Exception {
        mockMvc.perform(delete("/system/1"))
               .andExpect(status().isForbidden());
    }
}
