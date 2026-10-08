package com.mhamzah.gateway.engine;

import com.mhamzah.gateway.audit.AuditRecord;
import com.mhamzah.gateway.extension.GatewayResponse;

/** The client response plus the audit record describing how it was produced. */
public record ExecutionResult(GatewayResponse response, AuditRecord audit) {}
