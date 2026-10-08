package com.mhamzah.gateway.extension;

/**
 * Turns a {@link GatewayError} into the client response. Implement as a Spring bean and reference its
 * bean name from {@code gw_flow.error_handler}; flows without one use {@code defaultErrorHandler}.
 */
@FunctionalInterface
public interface ErrorHandler {

    GatewayResponse handle(GatewayError error, ExecutionContext ctx);
}
