package com.mhamzah.gateway.mapping;

/** Where a mapping rule writes its value. */
public enum TargetType {
    BODY,
    HEADER,
    QUERY,
    PATH,
    /** The HTTP status of the client response (FLOW_RESPONSE rules only); target_path is not used. */
    STATUS,
    /**
     * The client response is the file a step downloaded (FLOW_RESPONSE rules only): {@code source_path =
     * $.steps.<name>} of a step with {@code body_codec = fileCodec}; target_path is not used. The file goes out with its
     * Content-Type and Content-Disposition; BODY rules and the flow's response_handler then do not apply. When the step
     * has no file (it answered JSON), the response is built as usual.
     */
    FILE
}
