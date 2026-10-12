package com.mhamzah.gateway.studio.audit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.mhamzah.gateway.logging.CorrelationId;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;

/**
 * The recent log lines of each correlation ID, kept in memory for Studio's audit trail (the audit tables don't
 * store logs). Bounded: the newest {@code maxTransactions} correlation IDs, at most {@code maxLines} lines each.
 * Lines are only as long-lived as the process and only from this instance; the log output stays the record.
 * Log lines are already masked by the request/response logging.
 */
public class LogBuffer implements DisposableBean {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final int maxLines;
    private final Map<String, List<String>> lines;
    private final Appender appender = new Appender();

    public LogBuffer(int maxTransactions, int maxLines) {
        this.maxLines = maxLines;
        this.lines = new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > maxTransactions;
            }
        };
        // re-attached after a Logback reset (another context initializing logging), so it keeps collecting
        com.mhamzah.gateway.logging.RootAppenders.attach(appender, "gateway-studio-log-buffer");
    }

    /** The lines logged with this correlation ID, oldest first; empty when none (or already evicted). */
    public synchronized List<String> lines(String correlationId) {
        List<String> l = lines.get(correlationId);
        return l == null ? List.of() : List.copyOf(l);
    }

    private synchronized void add(String correlationId, String line) {
        List<String> l = lines.computeIfAbsent(correlationId, k -> new ArrayList<>());
        if (l.size() < maxLines) {
            l.add(line);
        } else if (l.size() == maxLines) {
            l.add("… further lines of this correlation ID were not kept (gateway.studio.log-buffer.max-lines)");
        }
    }

    @Override
    public void destroy() {
        com.mhamzah.gateway.logging.RootAppenders.detach(appender);
    }

    private final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            String id = event.getMDCPropertyMap().get(CorrelationId.MDC_KEY);
            if (id == null || id.isEmpty()) {
                return;
            }
            StringBuilder line = new StringBuilder()
                    .append(TIME.format(Instant.ofEpochMilli(event.getTimeStamp()))).append(' ')
                    .append(String.format("%-5s", event.getLevel())).append(" [").append(event.getThreadName())
                    .append("] ").append(shortName(event.getLoggerName())).append(" : ")
                    .append(event.getFormattedMessage());
            IThrowableProxy t = event.getThrowableProxy();
            if (t != null) {
                line.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            }
            add(id, line.toString());
        }

        private static String shortName(String logger) {
            int dot = logger.lastIndexOf('.');
            return dot < 0 ? logger : logger.substring(dot + 1);
        }
    }
}
