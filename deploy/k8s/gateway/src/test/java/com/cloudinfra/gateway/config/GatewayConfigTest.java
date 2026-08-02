package com.cloudinfra.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class GatewayConfigTest {

    @Mock
    private RouteLocatorBuilder routeLocatorBuilder;

    @Mock
    private RouteLocatorBuilder.Builder routeBuilder;

    @Mock
    private RouteLocatorBuilder.RoutePredicateSpec predicateSpec;

    @Mock
    private RouteLocatorBuilder.RouteFilterSpec filterSpec;

    @Mock
    private RouteLocator routeLocator;

    @Mock
    private Route route;

    private GatewayConfig gatewayConfig;

    @BeforeEach
    void setUp() {
        gatewayConfig = new GatewayConfig();
    }

    @Test
    void shouldCreateCorsFilter() {
        // When
        var corsFilter = gatewayConfig.corsFilter();

        // Then
        assertThat(corsFilter).isNotNull();
    }

    @Test
    void shouldConfigureCorsMappings() {
        // Given
        var registry = mock(org.springframework.web.servlet.config.annotation.CorsRegistry.class);
        var corsRegistration = mock(org.springframework.web.servlet.config.annotation.CorsRegistration.class);

        when(registry.addMapping("/**")).thenReturn(corsRegistration);
        when(corsRegistration.allowedOrigins("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowedMethods("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowedHeaders("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowCredentials(true)).thenReturn(corsRegistration);
        when(corsRegistration.maxAge(3600L)).thenReturn(corsRegistration);

        // When
        gatewayConfig.addCorsMappings(registry);

        // Then
        verify(registry).addMapping("/**");
        verify(corsRegistration).allowedOrigins("*");
        verify(corsRegistration).allowedMethods("*");
        verify(corsRegistration).allowedHeaders("*");
        verify(corsRegistration).allowCredentials(true);
        verify(corsRegistration).maxAge(3600L);
    }

    @Test
    void shouldCreateDefaultRoutes() {
        // Given
        when(routeLocatorBuilder.routes()).thenReturn(routeBuilder);
        when(routeBuilder.route(anyString(), any())).thenReturn(filterSpec);
        when(filterSpec.filters(any())).thenReturn(filterSpec);
        when(filterSpec.uri(anyString())).thenReturn(routeBuilder);
        when(routeBuilder.build()).thenReturn(routeLocator);

        // When
        var routeLocatorResult = gatewayConfig.defaultRoutes(routeLocatorBuilder);

        // Then
        assertThat(routeLocatorResult).isEqualTo(routeLocator);
        verify(routeLocatorBuilder).routes();
        verify(routeBuilder).route(eq("fallback"), any());
    }
}