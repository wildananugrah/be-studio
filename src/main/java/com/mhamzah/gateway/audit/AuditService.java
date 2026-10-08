package com.mhamzah.gateway.audit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Asynchronous audit trail (spec Section 10). Request threads only enqueue; one background thread writes
 * batches. A full queue or a failed write drops entries with a WARN/ERROR log and never affects clients.
 */
public class AuditService implements SmartLifecycle {

    /** Persists one batch; implemented by {@link AuditWriter}. */
    @FunctionalInterface
    public interface BatchWriter {
        void write(List<AuditRecord> batch);
    }

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private final BatchWriter writer;
    private final BlockingQueue<AuditRecord> queue;
    private final int batchSize;
    private volatile boolean running;
    private Thread worker;

    public AuditService(BatchWriter writer, int queueCapacity, int batchSize) {
        this.writer = writer;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
    }

    /** Enqueues without blocking; drops the record when the queue is full. */
    public void submit(AuditRecord record) {
        if (!queue.offer(record)) {
            log.warn("Audit queue full; dropped audit record correlationId={}", record.correlationId());
        }
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("audit-writer").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(SHUTDOWN_GRACE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!queue.isEmpty()) {
                log.warn("Shutting down with {} unwritten audit record(s)", queue.size());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stop after the web server so requests in flight can still enqueue. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            try {
                AuditRecord first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<AuditRecord> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                writeBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeBatch(List<AuditRecord> batch) {
        try {
            writer.write(batch);
        } catch (RuntimeException e) {
            log.error("Failed to write {} audit record(s); dropped correlationIds={}: {}", batch.size(),
                    batch.stream().map(AuditRecord::correlationId).toList(), e.getMessage());
        }
    }
}
