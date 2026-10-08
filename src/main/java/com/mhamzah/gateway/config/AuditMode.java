package com.mhamzah.gateway.config;

/** Per-flow audit override; INHERIT follows {@code gateway.audit.enabled}. */
public enum AuditMode {
    INHERIT,
    ON,
    OFF;

    public boolean resolve(boolean globalEnabled) {
        return this == ON || this == INHERIT && globalEnabled;
    }
}
