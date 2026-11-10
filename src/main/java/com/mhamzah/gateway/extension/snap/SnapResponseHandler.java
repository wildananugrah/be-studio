package com.mhamzah.gateway.extension.snap;

import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.extension.MessageView;
import com.mhamzah.gateway.mapping.MappingEngine;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SNAP success responses. Set
 * {@code gw_flow.response_handler = snapResponseHandler}: the body the flow's
 * rules
 * built gets {@code responseCode} ({@code <status><service code>00}, e.g.
 * {@code 2002400}) and {@code responseMessage}
 * (the catalog's {@code <status>.00}, e.g. "Successful" or "Request In
 * Progress") first, and {@code additionalInfo}
 * last. {@code additionalInfo} is optional: it is sent only when the flow's
 * rules set {@code $.additionalInfo.*} or
 * the flow has the named response {@code additionalInfo} and its rules produce
 * at least one field (the same one
 * {@link SnapErrorHandler} uses; its keys win).
 * Values the rules already set for {@code responseCode} or
 * {@code responseMessage} are kept. A status that cannot
 * carry a body (204, 205, 304, 1xx) is sent as
 * {@code gateway.snap.no-body-status} (200), with {@code responseCode}
 * still telling the original one ({@code 204G000}).
 */
@Component(SnapResponseHandler.BEAN_NAME)
public class SnapResponseHandler implements MessageHandler {

    public static final String BEAN_NAME = "snapResponseHandler";

    private static final Logger log = LoggerFactory.getLogger(SnapResponseHandler.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final Supplier<FlowRegistry> registry;
    private final MappingEngine mapping;
    private final int noBodyStatus;

    @Autowired
    public SnapResponseHandler(ObjectProvider<FlowRegistryHolder> holder, ObjectProvider<MappingEngine> mapping,
            ObjectProvider<GatewayProperties> properties) {
        this(() -> holder.getObject().current(), mapping.getIfAvailable(MappingEngine::new),
                SnapErrorHandler.noBodyStatus(properties));
    }

    public SnapResponseHandler(Supplier<FlowRegistry> registry, MappingEngine mapping) {
        this(registry, mapping, 200);
    }

    public SnapResponseHandler(Supplier<FlowRegistry> registry, MappingEngine mapping, int noBodyStatus) {
        this.registry = registry;
        this.mapping = mapping;
        this.noBodyStatus = noBodyStatus;
    }

    @Override
    public GatewayResponse handle(MessageView message, ExecutionContext ctx) {
        JsonNode body = message.body();
        if (body != null && !body.isNull() && !body.isObject()) {
            log.warn("Flow {}: the response body is not a JSON object, so no SNAP fields were added", ctx.flowCode());
            return null;
        }
        int status = message.status() == null ? 200 : message.status();
        ObjectNode in = body instanceof ObjectNode o ? o : F.objectNode();
        ObjectNode out = F.objectNode();
        SnapCodes.Code code = SnapCodes.find(status + ".00", ctx);
        out.set("responseCode", in.has("responseCode") ? in.get("responseCode")
                : F.stringNode(status + serviceCode(ctx) + "00"));
        out.set("responseMessage", in.has("responseMessage") ? in.get("responseMessage")
                : F.stringNode(message(status, code)));
        in.properties().forEach(e -> {
            if (!e.getKey().equals("responseCode") && !e.getKey().equals("responseMessage")
                    && !e.getKey().equals(SnapErrorHandler.ADDITIONAL_INFO)) {
                out.set(e.getKey(), e.getValue());
            }
        });
        ObjectNode info = in.get(SnapErrorHandler.ADDITIONAL_INFO) instanceof ObjectNode o ? o : null;
        ObjectNode named = SnapAdditionalInfo.build(SnapAdditionalInfo.flow(registry, ctx.flowCode()), mapping, ctx);
        if (named != null) {
            info = info == null ? named : info.setAll(named);
        }
        if (info != null) {
            out.set(SnapErrorHandler.ADDITIONAL_INFO, info);
        }
        message.setBody(out);
        if (message.status() != null) {
            message.setStatus(SnapCodes.sendable(status, noBodyStatus, ctx.flowCode()));
        }
        return null;
    }

    private static String serviceCode(ExecutionContext ctx) {
        return SnapCodes.responseServiceCode(ctx.serviceCode());
    }

    private static String message(int status, SnapCodes.Code code) {
        if (code != null && !code.message().isEmpty()) {
            return SnapCodes.fill(code.message(), null, null);
        }
        if (status >= 200 && status < 300) {
            return "Successful";
        }
        org.springframework.http.HttpStatus known = org.springframework.http.HttpStatus.resolve(status);
        return known == null ? "General Error" : known.getReasonPhrase();
    }
}