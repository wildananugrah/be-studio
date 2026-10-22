package com.mhamzah.gateway.mapping;

/** Where a mapping rule writes its value. */
public enum TargetType {
    BODY,
    HEADER,
    QUERY,
    PATH,
    /** The HTTP status of the client response (FLOW_RESPONSE rules only); target_path is not used. */
    STATUS
}
