package com.cloudinfra.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 网关 HTTP 客户端配置
 * 配置连接池、超时、重试以及跳过代理（用于内网服务调用）
 * 提供 RestClientCustomizer bean 供 Spring Cloud Gateway MVC 的 RestClientProxyExchange 使用
 * 同时提供 ClientHttpRequestFactory bean 供其他组件使用
 */
@Configuration
public class GatewayClientConfig {

    /** 连接建立超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** 从连接池获取连接超时 */
    private static final Duration CONNECTION_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** Socket 读超时（下游响应超时） */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 创建配置好的 ClientHttpRequestFactory
     * 配置跳过代理，直接连接目标服务（内网服务间调用不需要代理）
     */
    @Bean
    @Primary
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(2000);
        cm.setDefaultMaxPerRoute(500);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(CONNECTION_REQUEST_TIMEOUT))
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(READ_TIMEOUT))
                // 跳过代理，直接连接目标服务
                .setProxy(null)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                // 禁用系统代理设置
                .setProxy((HttpHost) null)
                .disableCookieManagement()
                .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    /**
     * 提供 RestClientCustomizer，确保 Spring Cloud Gateway MVC 的 RestClientProxyExchange
     * 使用我们配置的 ClientHttpRequestFactory（跳过代理、连接池、超时配置）
     * 注意：不能命名为 gatewayRestClientCustomizer，与 GatewayServerMvcAutoConfiguration
     * 中同名的 RestClientCustomizer bean 冲突（BeanDefinitionOverrideException）
     */
    @Bean
    public RestClientCustomizer gatewayHttpClientCustomizer(ClientHttpRequestFactory clientHttpRequestFactory) {
        return builder -> builder.requestFactory(clientHttpRequestFactory);
    }

    @Bean
    public RestClient restClient(ObservationRegistry observationRegistry, ClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }
}
