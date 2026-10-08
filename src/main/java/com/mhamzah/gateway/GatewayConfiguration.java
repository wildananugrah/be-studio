package com.mhamzah.gateway;

import com.mhamzah.gateway.audit.AuditService;
import com.mhamzah.gateway.audit.AuditWriter;
import com.mhamzah.gateway.codec.JsonCodec;
import com.mhamzah.gateway.codec.SoapCodec;
import com.mhamzah.gateway.codec.XmlCodec;
import com.mhamzah.gateway.config.ConfigCompiler;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.engine.FlowExecutor;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.invoke.DownstreamClient;
import com.mhamzah.gateway.invoke.HttpDownstreamClient;
import com.mhamzah.gateway.mapping.MappingEngine;
import com.mhamzah.gateway.masking.Masker;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    @Bean
    MappingEngine mappingEngine() {
        return new MappingEngine();
    }

    @Bean
    Masker masker(GatewayProperties properties) {
        return new Masker(properties.masking().fields(), properties.masking().mask());
    }

    @Bean
    ConfigCompiler configCompiler(BeanFactory beans, GatewayProperties properties, Environment environment) {
        ConfigCompiler.HandlerLookup lookup = new ConfigCompiler.HandlerLookup() {
            @Override
            public <T> T find(String name, Class<T> type) {
                return beans.containsBean(name) && beans.isTypeMatch(name, type) ? beans.getBean(name, type) : null;
            }
        };
        // ${...} in gw_target_system(_header) values resolve from environment variables and application config
        return new ConfigCompiler(lookup, properties.targetSystems(), environment::resolveRequiredPlaceholders,
                Duration.ofMillis(properties.defaultFlowTimeoutMs()), Duration.ofMillis(properties.defaultStepTimeoutMs()));
    }

    /** Built-in body codecs, referenced by bean name from gw_target_system.body_codec / gw_flow_step.body_codec. */
    @Bean(JsonCodec.BEAN_NAME)
    JsonCodec jsonCodec() {
        return JsonCodec.INSTANCE;
    }

    @Bean(XmlCodec.BEAN_NAME)
    XmlCodec xmlCodec() {
        return new XmlCodec();
    }

    @Bean(SoapCodec.SOAP_11_BEAN_NAME)
    SoapCodec soapCodec() {
        return SoapCodec.soap11();
    }

    @Bean(SoapCodec.SOAP_12_BEAN_NAME)
    SoapCodec soap12Codec() {
        return SoapCodec.soap12();
    }

    @Bean
    DownstreamClient downstreamClient() {
        return new HttpDownstreamClient();
    }

    @Bean(destroyMethod = "close")
    FlowExecutor flowExecutor(DownstreamClient client, MappingEngine mapping, DefaultErrorHandler defaultErrorHandler) {
        return new FlowExecutor(client, mapping, defaultErrorHandler);
    }

    /** Always created: even with the global switch off, flows with audit_mode=ON are audited. */
    @Bean
    AuditService auditService(DataSource dataSource, PlatformTransactionManager txManager,
            GatewayProperties properties, Masker masker) {
        GatewayProperties.Audit audit = properties.audit();
        AuditWriter writer = new AuditWriter(dataSource, txManager, properties.db(), masker, audit.storePayloads());
        return new AuditService(writer, audit.queueCapacity(), audit.batchSize());
    }
}
