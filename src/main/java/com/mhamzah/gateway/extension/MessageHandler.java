package com.mhamzah.gateway.extension;

/**
 * Custom logic over a whole message: HTTP headers plus JSON body. Implement as a Spring bean and
 * reference its bean name from a flow or step {@code request_handler} / {@code response_handler}.
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Mutate {@code message} in place.
     *
     * @return null to continue; a response to short-circuit the flow. Short-circuiting is only honored
     *     at the flow request hook; elsewhere a non-null return is ignored and a warning is logged.
     */
    GatewayResponse handle(MessageView message, ExecutionContext ctx);
}
