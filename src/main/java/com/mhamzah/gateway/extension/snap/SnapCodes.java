package com.mhamzah.gateway.extension.snap;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.mapping.LookupTable;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The SNAP response code catalog: {@code responseCode = <HTTP status, 3><service code, 2><case code, 2>}.
 *
 * <p>A SNAP code is written {@code "403.14"} (HTTP status, dot, case code). The catalog is the lookup table
 * {@value #CATALOG} in {@code gw_lookup_entry}: {@code source_value} {@code 403.14}, {@code target_value}
 * {@code {"message": "Insufficient Funds"}} (or the message as plain text). Messages may hold {@code {field name}}
 * and {@code [reason]} placeholders. The codes the gateway itself uses are built in, so a missing row never breaks a
 * response.
 */
public final class SnapCodes {

    /** {@code gw_lookup_entry.lookup_code} of the catalog. */
    public static final String CATALOG = "SNAP_RESPONSE_CODE";
    /** Lookup that maps the gateway's own errors ({@code ErrorType} names) to SNAP codes. */
    public static final String GATEWAY_ERRORS = "SNAP_GATEWAY_ERROR";
    /** Prefix of the per-downstream lookups: {@code SNAP_ERR_<target system code>}. */
    public static final String DOWNSTREAM_PREFIX = "SNAP_ERR_";
    /** Row of a downstream lookup that says where that system puts its error code. */
    public static final String CODE_PATH_KEY = "@codePath";

    private static final Logger log = LoggerFactory.getLogger(SnapCodes.class);
    private static final Pattern KEY = Pattern.compile("([1-5][0-9]{2})\\.([0-9A-Za-z]{2})");
    private static final Pattern REASON = Pattern.compile("\\s*\\[(?:reason|Reason|info)]");
    private static final Pattern FIELD = Pattern.compile("\\s*\\{field name}");

    private static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry("200.00", "Successful"),
            Map.entry("202.00", "Request In Progress"),
            Map.entry("400.00", "Bad Request"),
            Map.entry("400.01", "Invalid Field Format {field name}"),
            Map.entry("400.02", "Invalid Mandatory Field {field name}"),
            Map.entry("404.02", "Invalid Routing"),
            Map.entry("500.00", "General Error"),
            Map.entry("500.01", "Internal Server Error"),
            Map.entry("500.02", "External Server Error"),
            Map.entry("504.00", "Timeout"));

    /** A resolved SNAP code: HTTP status, two-character case code and the catalog message. */
    public record Code(int status, String caseCode, String message) {

        /** {@code responseCode} for a flow's service code ({@code 00} when the flow has none). */
        public String responseCode(String serviceCode) {
            return status + (serviceCode == null || serviceCode.isBlank() ? "00" : serviceCode) + caseCode;
        }
    }

    private SnapCodes() {}

    /** HTTP statuses whose response cannot carry a body (1xx, 204, 205, 304): a SNAP body sent with them is lost. */
    public static boolean hasNoBody(int status) {
        return status < 200 || status == 204 || status == 205 || status == 304;
    }

    /** The status to send a SNAP response with: {@code status}, or {@code noBodyStatus} when it cannot carry a body. */
    static int sendable(int status, int noBodyStatus, String flowCode) {
        if (!hasNoBody(status)) {
            return status;
        }
        log.warn(
                "Flow {}: HTTP {} cannot carry the SNAP body; sending it with HTTP {} (gateway.snap.no-body-status)",
                flowCode, status, noBodyStatus);
        return noBodyStatus;
    }

    /**
     * The {@code "403.14"} form of a lookup value. Plain text is used as it is; a JSON number (a target value written
     * without quotes, such as {@code 400.10}, which JSON reads as {@code 400.1}) gets its two decimals back.
     */
    public static String key(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return value.isValueNode() ? value.asString().strip() : null;
    }

    /** The catalog entry of {@code key}, else the built-in one; null when {@code key} is not a SNAP code. */
    public static Code find(String key, ExecutionContext ctx) {
        Matcher m = key == null ? null : KEY.matcher(key);
        if (m == null || !m.matches()) {
            return null;
        }
        String message = BUILT_IN.get(key);
        LookupTable catalog = ctx.lookupTable(CATALOG).orElse(null);
        JsonNode entry = catalog == null ? null : catalog.entries().get(key);
        if (entry != null && entry.isObject()) {
            message = entry.path("message").asString(message);
        } else if (entry != null && entry.isValueNode() && !entry.isNull()) {
            message = entry.asString();
        }
        return new Code(Integer.parseInt(m.group(1)), m.group(2), message == null ? "" : message);
    }

    /** The catalog entry of {@code key}, or Internal Server Error ({@code 500.01}) when there is none. */
    public static Code require(String key, ExecutionContext ctx) {
        Code c = find(key, ctx);
        return c != null ? c : find("500.01", ctx);
    }

    /**
     * Fills a message's placeholders: {@code {field name}} with {@code field} and {@code [reason]} (also
     * {@code [Reason]}, {@code [info]}) with {@code reason}; a placeholder without a value is dropped.
     */
    public static String fill(String message, String field, String reason) {
        if (message == null) {
            return "";
        }
        String out = FIELD.matcher(message).replaceAll(Matcher.quoteReplacement(blank(field) ? "" : " " + field));
        out = REASON.matcher(out).replaceAll(Matcher.quoteReplacement(blank(reason) ? "" : " " + reason));
        return out.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
