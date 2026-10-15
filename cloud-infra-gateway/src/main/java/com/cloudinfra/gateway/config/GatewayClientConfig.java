package com.cloudinfra.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * 网关 HTTP 客户端配置
 * 仅提供配置好的 ClientHttpRequestFactory 和 RestClient.Builder
 * 让 Spring Cloud Gateway MVC 的函数式路由 DSL (http() + lb()) 处理路由
 * 完全绕过 RestClientProxyExchange 的自动配置问题
 */
@Configuration
public class GatewayClientConfig {

    /**
     * 创建配置好的 ClientHttpRequestFactory
     * 配置跳过代理，直接连接目标服务（内网服务间调用不需要代理）
     *
     * <p>超时值来自 {@link GatewayHttpTimeoutProperties}，而非本类常量。
     * 此前这里声明过 {@code CONNECT_TIMEOUT} / {@code CONNECTION_REQUEST_TIMEOUT} /
     * {@code READ_TIMEOUT = ofSeconds(10)} 三个常量，却在 {@code RequestConfig} 里
     * 另写了一份字面量：两份互不引用，**改常量不会有任何效果**，
     * 而字面量才是实际生效的那个。已统一改为读取配置项。
     */
    @Bean
    @Primary
    public ClientHttpRequestFactory clientHttpRequestFactory(GatewayHttpTimeoutProperties timeouts) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(2000);
        cm.setDefaultMaxPerRoute(500);

        RequestConfig requestConfig = buildRequestConfig(timeouts);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .setProxy((HttpHost) null)
                .disableCookieManagement()
                .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    /**
     * 包级可见（而非 private）是有意为之：本方法存在的唯一目的是让
     * {@code GatewayHttpTimeoutWiringTest} 能断言超时真的来自配置项。
     * 若改成 private，唯一的验证途径只剩反射进 Apache HttpClient 私有字段。
     */
    RequestConfig buildRequestConfig(GatewayHttpTimeoutProperties timeouts) {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(timeouts.getConnectionRequest()))
                .setConnectTimeout(Timeout.of(timeouts.getConnect()))
                .setResponseTimeout(Timeout.of(timeouts.getRead()))
                .setProxy(null)
                .build();
    }

    /**
     * 提供配置完整的 RestClient.Builder
     * 用于函数式路由 DSL (http() + lb()) 创建 RestClient
     * 必须标记 @LoadBalanced：Gateway MVC 的 RestClientProxyExchange 通过
     * LoadBalancerInterceptor 在 RestClient 内部解析 lb:// 服务名，
     * 没有独立的 LoadBalancerClientFilter（该组件仅存在于 Gateway WebFlux 变体）。
     */
    @Bean
    @Primary
    @LoadBalanced
    public RestClient.Builder gatewayRestClientBuilder(ClientHttpRequestFactory clientHttpRequestFactory,
                                                       ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .requestFactory(clientHttpRequestFactory)
                .observationRegistry(observationRegistry)
                .messageConverters(converters -> {
                    converters.add(new MappingJackson2HttpMessageConverter());
                })
                .defaultStatusHandler(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (request, response) -> {
                            throw new IllegalStateException("HTTP error: " + response.getStatusCode());
                        });
    }
}
