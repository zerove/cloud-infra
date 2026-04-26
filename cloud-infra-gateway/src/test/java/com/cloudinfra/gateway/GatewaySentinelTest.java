package com.cloudinfra.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class GatewaySentinelTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void setupRules() {
        // 配置限流规则
        GatewayFlowRule rule = new GatewayFlowRule("test_route")
                .setCount(0)
                .setIntervalSec(1);
        GatewayRuleManager.loadGatewayRules(Collections.singleton(rule));
    }

    @Test
    void shouldReturn429WhenLimited() throws Exception {
        mockMvc.perform(get("/test_route")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("Too Many Requests"));
    }
}
