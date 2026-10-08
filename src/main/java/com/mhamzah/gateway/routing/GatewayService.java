package com.mhamzah.gateway.routing;

import com.mhamzah.gateway.audit.AuditService;
import com.mhamzah.gateway.config.FlowRegistry;
import com.mhamzah.gateway.config.FlowRegistryHolder;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.engine.ExecutionResult;
import com.mhamzah.gateway.engine.FlowExecutor;
import com.mhamzah.gateway.engine.InboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Routes an inbound request to its flow, runs it, and hands the result to the audit trail. */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final FlowRegistryHolder registryHolder;
    private final FlowExecutor executor;
    private final AuditService audit;
    private final boolean auditEnabledGlobally;

    public GatewayService(FlowRegistryHolder registryHolder, FlowExecutor executor, AuditService audit,
            GatewayProperties properties) {
        this.registryHolder = registryHolder;
        this.executor = executor;
        this.audit = audit;
        this.auditEnabledGlobally = properties.audit().enabled();
    }

    public ExecutionResult handle(InboundRequest request, String correlationId) {
        // captured once: a concurrent reload does not affect this request
        FlowRegistry registry = registryHolder.current();
        MDC.put("correlationId", correlationId);
        try {
            var match = registry.match(request.method(), request.path());
            ExecutionResult result;
            boolean audited;
            if (match.isPresent()) {
                MDC.put("flowCode", match.get().flow().code());
                result = executor.execute(registry, match.get(), request, correlationId);
                audited = match.get().flow().auditMode().resolve(auditEnabledGlobally);
            } else {
                result = executor.notFound(request, correlationId);
                audited = auditEnabledGlobally;
            }
            log.info("{} {} flow={} status={} {}ms", request.method(), request.path(), result.audit().flowCode(),
                    result.response().status(), result.audit().durationMs());
            if (audited) {
                audit.submit(result.audit());
            }
            return result;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("flowCode");
        }
    }
}
