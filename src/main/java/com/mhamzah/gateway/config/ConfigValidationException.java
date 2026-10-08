package com.mhamzah.gateway.config;

import java.util.List;

/** The configuration is invalid; {@link #errors()} lists every problem found. */
public class ConfigValidationException extends RuntimeException {

    private final List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("Invalid gateway configuration (" + errors.size() + " error(s)):\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
