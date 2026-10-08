package com.mhamzah.gateway.extension;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A typed gateway failure. Thrown inside the engine and handed to the flow's {@link ErrorHandler}.
 * Downstream fields are set when a downstream response (or partial response) exists.
 */
public class GatewayError extends RuntimeException {

    private final ErrorType type;
    private final String stepName;
    private final Integer downstreamStatus;
    private final Map<String, String> downstreamHeaders;
    private final JsonNode downstreamBody;
    private final List<String> details;
    private final boolean clientError;

    private GatewayError(Builder b) {
        super(b.message != null ? b.message : b.type.defaultMessage(), b.cause);
        this.type = b.type;
        this.stepName = b.stepName;
        this.downstreamStatus = b.downstreamStatus;
        this.downstreamHeaders = b.downstreamHeaders == null ? Map.of() : Map.copyOf(b.downstreamHeaders);
        this.downstreamBody = b.downstreamBody;
        this.details = b.details == null ? List.of() : List.copyOf(b.details);
        this.clientError = b.clientError;
    }

    public static Builder of(ErrorType type) {
        return new Builder(type);
    }

    public ErrorType type() {
        return type;
    }

    /** Name of the step that failed, or null when the failure is not tied to a step. */
    public String stepName() {
        return stepName;
    }

    public Integer downstreamStatus() {
        return downstreamStatus;
    }

    public Map<String, String> downstreamHeaders() {
        return downstreamHeaders;
    }

    public JsonNode downstreamBody() {
        return downstreamBody;
    }

    /** Validation or mapping messages that are safe to return to the client. */
    public List<String> details() {
        return details;
    }

    /** True for a {@link ErrorType#MAPPING_ERROR} caused by the client's request ({@code $.request.*} source). */
    public boolean clientError() {
        return clientError;
    }

    public static final class Builder {
        private final ErrorType type;
        private String message;
        private Throwable cause;
        private String stepName;
        private Integer downstreamStatus;
        private Map<String, String> downstreamHeaders;
        private JsonNode downstreamBody;
        private List<String> details;
        private boolean clientError;

        private Builder(ErrorType type) {
            this.type = type;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder step(String stepName) {
            this.stepName = stepName;
            return this;
        }

        public Builder downstream(Integer status, Map<String, String> headers, JsonNode body) {
            this.downstreamStatus = status;
            this.downstreamHeaders = headers;
            this.downstreamBody = body;
            return this;
        }

        public Builder details(List<String> details) {
            this.details = details;
            return this;
        }

        public Builder clientError(boolean clientError) {
            this.clientError = clientError;
            return this;
        }

        public GatewayError build() {
            return new GatewayError(this);
        }
    }
}
