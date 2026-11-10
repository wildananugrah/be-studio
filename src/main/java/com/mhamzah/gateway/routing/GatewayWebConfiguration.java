package com.mhamzah.gateway.routing;

import com.mhamzah.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@code gateway.api-base-path} in front of every {@link GatewayController} mapping. */
@Configuration(proxyBeanMethods = false)
public class GatewayWebConfiguration implements WebMvcConfigurer {

    private final String apiBasePath;

    public GatewayWebConfiguration(GatewayProperties properties) {
        this.apiBasePath = properties.apiBasePath();
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(apiBasePath, HandlerTypePredicate.forAssignableType(GatewayController.class));
    }
}
