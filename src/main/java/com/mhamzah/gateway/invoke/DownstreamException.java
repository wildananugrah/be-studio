package com.mhamzah.gateway.invoke;

/** No downstream response was received. */
public class DownstreamException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        CONNECTION
    }

    private final Kind kind;

    public DownstreamException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
