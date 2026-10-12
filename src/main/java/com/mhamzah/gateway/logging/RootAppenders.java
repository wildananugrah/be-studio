package com.mhamzah.gateway.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.LoggerFactory;

/**
 * In-process appenders on the root logger (Studio's log buffer, the unit test recorder). Spring initializing logging
 * again (another application context in the same JVM, a logging reconfiguration) stops and resets Logback, which
 * detaches every appender and drops every listener; {@link #ensureAttached()}, called when a request starts, puts
 * them back so they do not silently stop collecting.
 */
public final class RootAppenders {

    private static final Set<Appender<ILoggingEvent>> ATTACHED = new CopyOnWriteArraySet<>();

    private RootAppenders() {}

    public static void attach(Appender<ILoggingEvent> appender, String name) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            appender.setContext(context);
            appender.setName(name);
            ATTACHED.add(appender);
            add(context, appender);
        }
    }

    public static void detach(Appender<ILoggingEvent> appender) {
        ATTACHED.remove(appender);
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
    }

    /** Re-attaches (and restarts) any registered appender a Logback reset removed. Cheap when nothing changed. */
    public static void ensureAttached() {
        if (ATTACHED.isEmpty() || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        for (Appender<ILoggingEvent> appender : ATTACHED) {
            add(context, appender);
        }
    }

    private static void add(LoggerContext context, Appender<ILoggingEvent> appender) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (!appender.isStarted()) {
            appender.start();
        }
        if (!root.isAttached(appender)) {
            root.addAppender(appender);
        }
    }
}
