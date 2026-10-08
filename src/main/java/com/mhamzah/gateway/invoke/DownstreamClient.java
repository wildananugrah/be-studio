package com.mhamzah.gateway.invoke;

/** Performs one HTTP call to a downstream system. */
public interface DownstreamClient {

    /**
     * Returns any HTTP response, including non-2xx ones.
     *
     * @throws DownstreamException when no response was received (timeout, connection failure)
     */
    DownstreamResponse call(DownstreamRequest request);
}
