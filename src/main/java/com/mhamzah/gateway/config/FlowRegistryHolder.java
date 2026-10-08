package com.mhamzah.gateway.config;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Holds the current {@link FlowRegistry}. Loads it once all beans exist (after Liquibase has migrated);
 * a failure there stops the application. {@link #reload()} swaps the snapshot atomically, so requests in
 * flight keep the snapshot they started with.
 */
@Component
public class FlowRegistryHolder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FlowRegistryHolder.class);

    private final ConfigLoader loader;
    private final ConfigCompiler compiler;
    private final AtomicReference<FlowRegistry> current = new AtomicReference<>();

    public FlowRegistryHolder(ConfigLoader loader, ConfigCompiler compiler) {
        this.loader = loader;
        this.compiler = compiler;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reload();
    }

    public FlowRegistry current() {
        return current.get();
    }

    /**
     * Loads, validates and installs a new snapshot.
     *
     * @throws ConfigValidationException when the config is invalid; the current snapshot stays in place
     */
    public synchronized FlowRegistry reload() {
        FlowRegistry registry = compiler.compile(loader.load());
        current.set(registry);
        log.info("Loaded gateway configuration: {} flow(s), {} lookup table(s)",
                registry.flows().size(), registry.lookups().size());
        registry.flows().forEach(f -> log.info("  {} {} -> flow {} ({} step(s))",
                f.method(), f.pathPattern().getPatternString(), f.code(), f.allSteps().size()));
        registry.targetSystems().values().forEach(t -> log.info("  target {} -> {} ({}{})", t.code(),
                withoutUserInfo(t.system().baseUrl()),
                t.source() == ResolvedTarget.Source.DATABASE ? "database" : "application config",
                t.overridesConfig() ? ", overrides application config" : ""));
        return registry;
    }

    /** Never log credentials embedded in a URL (http://user:pass@host). */
    private static String withoutUserInfo(String url) {
        return url == null ? null : url.replaceFirst("://[^/@]*@", "://***@");
    }
}
