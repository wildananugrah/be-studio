package com.mhamzah.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private static AuditRecord record(String id) {
        return new AuditRecord(id, "F", "GET", "/x", 200, null, null, null, null, Instant.now(), 1, List.of());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }

    @Test
    void writesSubmittedRecordsInBackground() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        AuditService service = new AuditService(batch -> batch.forEach(r -> written.add(r.correlationId())), 100, 10);
        service.start();
        service.submit(record("a"));
        service.submit(record("b"));
        awaitTrue(() -> written.size() == 2);
        service.stop();
        assertThat(written).containsExactly("a", "b");
    }

    @Test
    void submitNeverBlocksAndDropsWhenQueueIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        AuditService service = new AuditService(batch -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.addAndGet(batch.size());
        }, 2, 1);
        service.start();
        service.submit(record("taken-by-writer"));
        Thread.sleep(300); // writer is now blocked inside the first batch
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            service.submit(record("r" + i));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
        release.countDown();
        awaitTrue(() -> written.get() == 3);
        service.stop();
        assertThat(written.get()).isEqualTo(3); // 1 in flight + queue capacity 2; the other 8 were dropped
    }

    @Test
    void writerFailureIsSwallowedAndLaterBatchesStillWritten() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        AuditService service = new AuditService(batch -> {
            if (batch.getFirst().correlationId().equals("bad")) {
                throw new IllegalStateException("db down");
            }
            batch.forEach(r -> written.add(r.correlationId()));
        }, 100, 1);
        service.start();
        service.submit(record("bad"));
        service.submit(record("good"));
        awaitTrue(() -> written.contains("good"));
        service.stop();
        assertThat(written).containsExactly("good");
    }

    @Test
    void stopDrainsTheQueue() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        AuditService service = new AuditService(batch -> batch.forEach(r -> written.add(r.correlationId())), 100, 5);
        service.start();
        for (int i = 0; i < 20; i++) {
            service.submit(record("r" + i));
        }
        service.stop();
        assertThat(written).hasSize(20);
    }
}
