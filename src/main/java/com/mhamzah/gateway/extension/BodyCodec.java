package com.mhamzah.gateway.extension;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Converts a step's body between the JSON the gateway works with and the format a downstream speaks on the wire
 * (XML, SOAP, ...). Mapping rules, handlers, schemas, expressions and the audit trail always see JSON; only the
 * HTTP call uses the encoded form. Implement as a Spring bean and reference its bean name from
 * {@code gw_target_system.body_codec} (default for every step calling that system) or
 * {@code gw_flow_step.body_codec} (wins over the system's). Built in: {@code xmlCodec}, {@code soapCodec},
 * {@code soap12Codec}. Implementations must be thread-safe.
 */
public interface BodyCodec {

    /** Content-Type of an encoded request body. A Content-Type set in the headers by {@link #encode} wins. */
    String contentType();

    /** Accept header sent on every call. */
    default String accept() {
        return contentType();
    }

    /**
     * Encodes the request body (after the step request handler ran). Never called when no body is sent.
     *
     * @param headers the outgoing headers: mutable and case-insensitive, e.g. to add a signature over the encoded body
     * @throws IllegalArgumentException when the JSON cannot be encoded; the step fails with {@code MAPPING_ERROR}
     */
    String encode(JsonNode body, Map<String, String> headers, ExecutionContext ctx);

    /**
     * Decodes a non-empty response body, for 2xx and error responses alike.
     *
     * @param headers response headers, lower-case names
     * @throws IllegalArgumentException when the body is not in the expected format; on a 2xx response the step
     *     fails with {@code DOWNSTREAM_INVALID_RESPONSE}
     */
    JsonNode decode(String body, Map<String, String> headers);
}
