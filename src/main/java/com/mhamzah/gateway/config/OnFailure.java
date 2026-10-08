package com.mhamzah.gateway.config;

/** What a failed step does to its flow. */
public enum OnFailure {
    /** Stop the flow and hand the error to the flow's error handler. */
    STOP,
    /** Record the failure in {@code $.steps.<name>} and carry on. */
    CONTINUE
}
