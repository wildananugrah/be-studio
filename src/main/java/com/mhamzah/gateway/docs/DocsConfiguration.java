package com.mhamzah.gateway.docs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.LiteWebJarsResourceResolver;

/**
 * The Swagger UI files ({@code /webjars/swagger-ui/...}, version-less through webjars-locator-lite) for
 * {@link DocsController}'s page; like the page, only when {@code gateway.docs.enabled=true}
 * ({@code spring.web.resources.add-mappings} is off, so nothing else serves them).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("gateway.docs.enabled")
public class DocsConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // swagger-ui is the only webjar on the classpath
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .resourceChain(true)
                .addResolver(new LiteWebJarsResourceResolver());
    }
}
