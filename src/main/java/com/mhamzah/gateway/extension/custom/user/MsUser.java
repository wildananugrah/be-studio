package com.mhamzah.gateway.extension.custom.user;

import java.time.Instant;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** A row of {@code tbl_ms_user}. */
public record MsUser(
        long id,
        String username,
        String fullName,
        String email,
        String phone,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    /** The JSON the API returns for a user. */
    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("id", id);
        o.put("username", username);
        o.put("fullName", fullName);
        o.put("email", email);
        o.put("phone", phone);
        o.put("status", status);
        o.put("createdAt", createdAt == null ? null : createdAt.toString());
        o.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        return o;
    }
}
