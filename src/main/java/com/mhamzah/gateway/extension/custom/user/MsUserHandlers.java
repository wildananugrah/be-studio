package com.mhamzah.gateway.extension.custom.user;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Example: a CRUD API over a custom table ({@code tbl_ms_user}) built from flows without downstream steps. Each
 * operation is a {@link MessageHandler} bean at the <b>flow request</b> hook that answers directly (short-circuit),
 * so the gateway still does routing, schema validation, correlation IDs, logging and audit.
 *
 * <pre>
 * POST   /api/v1/users          request_handler = msUserCreate   201 + the user (Location header)
 * GET    /api/v1/users          request_handler = msUserList     200 {content, page, size, totalElements, totalPages}
 *                               ?page=1&amp;size=10&amp;status=ACTIVE&amp;search=budi
 * GET    /api/v1/users/{id}     request_handler = msUserDetail   200 the user | 404
 * PUT    /api/v1/users/{id}     request_handler = msUserUpdate   200 the updated user | 404
 * DELETE /api/v1/users/{id}     request_handler = msUserDelete   204 | 404
 * </pre>
 *
 * The flows are in {@code db/changelog/changes/102-dev-demo-ms-user.xml}; errors use the gateway's default shape
 * ({@code errorCode, errorMessage, correlationId, details}).
 */
@Configuration(proxyBeanMethods = false)
public class MsUserHandlers {

    static final int DEFAULT_SIZE = 10;
    static final int MAX_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{6,20}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final MsUserRepository users;
    private final String apiBasePath;

    public MsUserHandlers(MsUserRepository users, @Value("${gateway.api-base-path:/api}") String apiBasePath) {
        this.users = users;
        this.apiBasePath = apiBasePath;
    }

    /** POST: creates a user from {@code {username, fullName, email?, phone?, status?}}. */
    @Bean
    MessageHandler msUserCreate() {
        return (message, ctx) -> {
            JsonNode body = message.body();
            List<String> errors = new ArrayList<>();
            String username = text(body, "username");
            if (username == null) {
                errors.add("username is required");
            } else if (!USERNAME.matcher(username).matches()) {
                errors.add("username must be 3-50 letters, digits, '.', '_' or '-'");
            }
            String fullName = text(body, "fullName");
            if (fullName == null) {
                errors.add("fullName is required");
            }
            Fields f = fields(body, errors);
            if (!errors.isEmpty()) {
                return error(400, "VALIDATION_FAILED", "Invalid user", errors, ctx);
            }
            try {
                MsUser created = users.create(username, fullName, f.email, f.phone, f.status == null ? "ACTIVE" : f.status);
                return new GatewayResponse(201, Map.of("Location", apiBasePath + "/v1/users/" + created.id()),
                        created.toJson());
            } catch (DuplicateKeyException e) {
                return error(409, "USERNAME_TAKEN", "Username '" + username + "' already exists", List.of(), ctx);
            }
        };
    }

    /** GET list: {@code ?page} (from 1), {@code size} (1-100, default 10), {@code status}, {@code search}. */
    @Bean
    MessageHandler msUserList() {
        return (message, ctx) -> {
            List<String> errors = new ArrayList<>();
            int page = intParam(ctx, "page", 1, 1, Integer.MAX_VALUE, errors);
            int size = intParam(ctx, "size", DEFAULT_SIZE, 1, MAX_SIZE, errors);
            String status = upper(query(ctx, "status"));
            if (status != null && !STATUSES.contains(status)) {
                errors.add("status must be one of " + STATUSES);
            }
            if (!errors.isEmpty()) {
                return error(400, "INVALID_QUERY", "Invalid query parameters", errors, ctx);
            }
            MsUserRepository.Page result = users.page(page, size, new MsUserRepository.Filter(status, query(ctx, "search")));
            ObjectNode out = F.objectNode();
            ArrayNode content = out.putArray("content");
            result.content().forEach(u -> content.add(u.toJson()));
            out.put("page", page);
            out.put("size", size);
            out.put("totalElements", result.totalElements());
            out.put("totalPages", (result.totalElements() + size - 1) / size);
            return GatewayResponse.of(200, out);
        };
    }

    /** GET detail: {@code /users/{id}}. */
    @Bean
    MessageHandler msUserDetail() {
        return (message, ctx) -> withId(ctx, id -> users.findById(id)
                .map(u -> GatewayResponse.of(200, u.toJson()))
                .orElseGet(() -> notFound(id, ctx)));
    }

    /** PUT: updates any of {@code fullName, email, phone, status}; {@code username} cannot change. */
    @Bean
    MessageHandler msUserUpdate() {
        return (message, ctx) -> withId(ctx, id -> {
            JsonNode body = message.body();
            List<String> errors = new ArrayList<>();
            if (body != null && body.has("username")) {
                errors.add("username cannot be changed");
            }
            String fullName = text(body, "fullName");
            Fields f = fields(body, errors);
            if (errors.isEmpty() && fullName == null && f.email == null && f.phone == null && f.status == null) {
                errors.add("send at least one of fullName, email, phone, status");
            }
            if (!errors.isEmpty()) {
                return error(400, "VALIDATION_FAILED", "Invalid user", errors, ctx);
            }
            Optional<MsUser> updated = users.update(id, fullName, f.email, f.phone, f.status);
            return updated.map(u -> GatewayResponse.of(200, u.toJson())).orElseGet(() -> notFound(id, ctx));
        });
    }

    /** DELETE: {@code /users/{id}}, 204 without body. */
    @Bean
    MessageHandler msUserDelete() {
        return (message, ctx) -> withId(ctx, id -> users.delete(id)
                ? new GatewayResponse(204, Map.of(), null)
                : notFound(id, ctx));
    }

    // ---------------------------------------------------------------- helpers

    private record Fields(String email, String phone, String status) {}

    private static Fields fields(JsonNode body, List<String> errors) {
        String email = text(body, "email");
        if (email != null && (email.length() > 100 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
            errors.add("email is not a valid address");
        }
        String phone = text(body, "phone");
        if (phone != null && !PHONE.matcher(phone).matches()) {
            errors.add("phone must be 6-20 digits, optionally starting with '+'");
        }
        String status = upper(text(body, "status"));
        if (status != null && !STATUSES.contains(status)) {
            errors.add("status must be one of " + STATUSES);
        }
        String fullName = text(body, "fullName");
        if (fullName != null && fullName.length() > 100) {
            errors.add("fullName must be at most 100 characters");
        }
        return new Fields(email, phone, status);
    }

    private interface IdAction {
        GatewayResponse apply(long id);
    }

    private static GatewayResponse withId(ExecutionContext ctx, IdAction action) {
        JsonNode id = ctx.read("$.request.path.id");
        long value;
        try {
            value = Long.parseLong(id == null ? "" : id.asString());
        } catch (NumberFormatException e) {
            return error(400, "INVALID_ID", "id must be a number", List.of(), ctx);
        }
        return action.apply(value);
    }

    private static GatewayResponse notFound(long id, ExecutionContext ctx) {
        return error(404, "USER_NOT_FOUND", "User " + id + " not found", List.of(), ctx);
    }

    private static GatewayResponse error(int status, String code, String message, List<String> details,
            ExecutionContext ctx) {
        ObjectNode body = F.objectNode();
        body.put("errorCode", code);
        body.put("errorMessage", message);
        body.put("correlationId", ctx.correlationId());
        if (!details.isEmpty()) {
            ArrayNode d = body.putArray("details");
            details.forEach(d::add);
        }
        return GatewayResponse.of(status, body);
    }

    private static int intParam(ExecutionContext ctx, String name, int fallback, int min, int max, List<String> errors) {
        String raw = query(ctx, name);
        if (raw == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v >= min && v <= max) {
                return v;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        errors.add(name + " must be a whole number" + (max == Integer.MAX_VALUE ? " >= " + min : " from " + min + " to " + max));
        return fallback;
    }

    private static String query(ExecutionContext ctx, String name) {
        JsonNode v = ctx.read("$.request.query." + name);
        return v == null || v.asString().isBlank() ? null : v.asString().trim();
    }

    private static String text(JsonNode body, String field) {
        JsonNode v = body == null ? null : body.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString().trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
