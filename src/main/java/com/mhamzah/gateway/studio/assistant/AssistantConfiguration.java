package com.mhamzah.gateway.studio.assistant;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.studio.StudioService;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Studio's project assistant, only when both {@code gateway.studio.enabled} and {@code gateway.assistant.enabled}
 * are true. Switched off, nothing reads the AI settings or calls the AI endpoint, and Studio hides the Ask button.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("gateway.studio.enabled")
@ConditionalOnBooleanProperty(name = "gateway.assistant.enabled", matchIfMissing = true)
public class AssistantConfiguration {

    /** AI_BASE_URL / AI_AUTH_KEY / AI_MODEL / AI_EFFORT / AI_AUTH_TYPE from the environment, else from the env file. */
    @Bean
    StudioAssistant studioAssistant(Environment environment, StudioService studio, GatewayProperties properties) {
        GatewayProperties.Assistant a = properties.assistant();
        return new StudioAssistant(AssistantSettings.load(environment, Path.of(a.envFile())), studio,
                a.maxTokens(), a.historyMessages());
    }
}
