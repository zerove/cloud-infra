package com.cloudinfra.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@ExtendWith(MockitoExtension.class)
class WebMvcCompressionConfigTest {

    @Mock
    private ConfigurableServletWebServerFactory servletWebServerFactory;

    private WebMvcCompressionConfig webMvcCompressionConfig;

    @Test
    void shouldConfigureCorsMappings() {
        // Given
        var corsRegistry = mock(CorsRegistry.class);
        var corsRegistration = mock(org.springframework.web.servlet.config.annotation.CorsRegistration.class);

        when(corsRegistry.addMapping("/**")).thenReturn(corsRegistration);
        when(corsRegistration.allowedOrigins("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowedMethods("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowedHeaders("*")).thenReturn(corsRegistration);
        when(corsRegistration.allowCredentials(true)).thenReturn(corsRegistration);
        when(corsRegistration.maxAge(3600L)).thenReturn(corsRegistration);

        // When
        var config = new WebMvcCompressionConfig();
        config.addCorsMappings(new CorsRegistry() {
            @Override
            public CorsRegistration addMapping(String pathPattern) {
                return new CorsRegistration() {
                    @Override
                    public CorsRegistration allowedOrigins(String... origins) { return this; }
                    @Override
                    public CorsRegistration allowedMethods(String... methods) { return this; }
                    @Override
                    public CorsRegistration allowedHeaders(String... headers) { return this; }
                    @Override
                    public CorsRegistration allowCredentials(boolean allowCredentials) { return this; }
                    @Override
                    public CorsRegistration maxAge(long maxAge) { return this; }
                    @Override
                    public CorsRegistration exposedHeaders(String... headers) { return this; }
                };
            }
        });

        // Verify no exception thrown
    }
}