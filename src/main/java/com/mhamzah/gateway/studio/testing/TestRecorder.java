package com.mhamzah.gateway.studio.testing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.mhamzah.gateway.invoke.DownstreamClient;
import com.mhamzah.gateway.invoke.DownstreamRequest;
import com.mhamzah.gateway.invoke.DownstreamResponse;
import com.mhamzah.gateway.logging.CorrelationId;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Captures, for the correlation IDs of running Studio tests only, every downstream call (by wrapping the
 * {@link DownstreamClient} bean) and every log line (with a Logback appender on the root logger). Everything else
 * passes through untouched.
 */
public class TestRecorder implements BeanPostProcessor, DisposableBean {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_LOG_LINES = 500;

    /** What one correlation ID collected so far. */
    public static final class Capture {
        public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        public final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    }

    /** A raw downstream call: {@code response} null and {@code error} set when no response arrived. */
    public record Call(DownstreamRequest request, DownstreamResponse response, long durationMs, String error) {}

    private final Map<String, Capture> active = new ConcurrentHashMap<>();
    private final Appender appender = new Appender();

    public TestRecorder() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            appender.setContext(context);
            appender.setName("gateway-studio-tests");
            appender.start();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
    }

    public Capture start(String correlationId) {
        Capture c = new Capture();
        active.put(correlationId, c);
        return c;
    }

    public void stop(String correlationId) {
        active.remove(correlationId);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof DownstreamClient client && !(bean instanceof Recording) ? new Recording(client) : bean;
    }

    @Override
    public void destroy() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
    }

    private Capture captureOf(Map<String, String> headers) {
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase(CorrelationId.HEADER)) {
                return active.get(h.getValue());
            }
        }
        return null;
    }

    private final class Recording implements DownstreamClient {
        private final DownstreamClient delegate;

        Recording(DownstreamClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public DownstreamResponse call(DownstreamRequest request) {
            Capture capture = active.isEmpty() ? null : captureOf(request.headers());
            if (capture == null) {
                return delegate.call(request);
            }
            long start = System.nanoTime();
            try {
                DownstreamResponse response = delegate.call(request);
                capture.calls.add(new Call(request, response, (System.nanoTime() - start) / 1_000_000, null));
                return response;
            } catch (RuntimeException e) {
                capture.calls.add(new Call(request, null, (System.nanoTime() - start) / 1_000_000, e.getMessage()));
                throw e;
            }
        }
    }

    private final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            if (active.isEmpty()) {
                return;
            }
            String id = event.getMDCPropertyMap().get(CorrelationId.MDC_KEY);
            Capture capture = id == null ? null : active.get(id);
            if (capture == null || capture.logs.size() >= MAX_LOG_LINES) {
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
            capture.logs.add(line.toString());
        }

        private static String shortName(String logger) {
            int dot = logger.lastIndexOf('.');
            return dot < 0 ? logger : logger.substring(dot + 1);
        }
    }
}
