package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.extension.MessageHandler;
import com.mhamzah.gateway.extension.MessageView;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Example {@link MessageHandler} at the <b>flow request</b> hook: only lets requests from known channels through.
 * Returning a response short-circuits the flow, so no downstream system is called.
 *
 * <p>Use it on a flow: {@code gw_flow.request_handler = 'channelGuard'}.
 * Allowed channels: {@code custom.channel-guard.allowed} (comma-separated, default {@code MOBILE,ATM,WEB}).
 */
@Component("channelGuard")
public class ChannelGuard implements MessageHandler {

    private final Set<String> allowed;

    public ChannelGuard(@Value("${custom.channel-guard.allowed:MOBILE,ATM,WEB}") List<String> allowed) {
        this.allowed = allowed.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public GatewayResponse handle(MessageView message, ExecutionContext ctx) {
        String channel = message.headers().get("X-Channel"); // header map is case-insensitive
        if (channel != null && allowed.contains(channel.trim().toUpperCase(Locale.ROOT))) {
            return null; // continue with the flow
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("errorCode", "CHANNEL_NOT_ALLOWED");
        body.put("errorMessage", channel == null
                ? "X-Channel header is required"
                : "Channel '" + channel + "' is not allowed");
        body.put("correlationId", ctx.correlationId());
        return GatewayResponse.of(403, body);
    }
}
