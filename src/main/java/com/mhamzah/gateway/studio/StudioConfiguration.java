package com.mhamzah.gateway.studio;

import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.ConfigLoader;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.mapping.MappingEngine;
import com.mhamzah.gateway.masking.Masker;
import com.mhamzah.gateway.studio.audit.AuditQueries;
import com.mhamzah.gateway.studio.audit.LogBuffer;
import com.mhamzah.gateway.studio.testing.TestRecorder;
import com.mhamzah.gateway.studio.testing.TestRunner;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gateway Studio, only when {@code gateway.studio.enabled=true}: the single-page UI is served from
 * {@code classpath:/studio/} at {@code /studio/}; {@link StudioController} and
 * {@link com.mhamzah.gateway.studio.testing.StudioTestController} are its API.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("gateway.studio.enabled")
public class StudioConfiguration implements WebMvcConfigurer {

    @Bean
    StudioService studioService(ConfigLoader loader, ConfigCompiler compiler, FlowRegistryHolder holder,
            MappingEngine mapping, ListableBeanFactory beans, GatewayProperties properties, DataSource dataSource,
            PlatformTransactionManager txManager) {
        return new StudioService(loader, compiler, holder, mapping, beans, properties, dataSource, txManager);
    }

    /** Recent log lines per correlation ID, for the audit trail screen. */
    @Bean
    LogBuffer studioLogBuffer(GatewayProperties properties) {
        GatewayProperties.Studio.LogBuffer b = properties.studio().logBuffer();
        return new LogBuffer(b.maxTransactions(), b.maxLines());
    }

    @Bean
    AuditQueries studioAuditQueries(DataSource dataSource, GatewayProperties properties, LogBuffer logs) {
        return new AuditQueries(dataSource, properties, logs);
    }

    /** Static: it wraps the DownstreamClient bean, so it must exist before that bean is created. */
    @Bean
    static TestRecorder studioTestRecorder() {
        return new TestRecorder();
    }

    @Bean
    TestRunner studioTestRunner(FlowRegistryHolder holder, GatewayProperties properties, Environment environment,
            TestRecorder recorder, Masker masker, DataSource dataSource) {
        return new TestRunner(holder, properties, environment, recorder, masker, dataSource);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/studio", "/studio/");
        registry.addViewController("/studio/").setViewName("forward:/studio/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/studio/**").addResourceLocations("classpath:/studio/");
    }
}
