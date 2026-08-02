package com.cloudinfra.gateway;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

public class GatewaySentinelTest extends BaseIntegrationTest {

    @MockitoBean
    private NacosConfigManager nacosConfigManager;

    @Test
    void contextLoads() {
    }
}
