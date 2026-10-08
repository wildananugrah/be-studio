package com.mhamzah.gateway.config;

/**
 * The effective definition of a downstream system and where it came from.
 *
 * @param overridesConfig true when a database row replaced a definition from application.yml
 */
public record ResolvedTarget(String code, GatewayProperties.TargetSystem system, Source source, boolean overridesConfig) {

    public enum Source {
        /** {@code gw_target_system} (reloadable). */
        DATABASE,
        /** {@code gateway.target-systems} in application.yml / environment (restart needed). */
        CONFIG
    }
}
