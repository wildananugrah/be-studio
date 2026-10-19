package com.mhamzah.gateway.audit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AuditPartitions#maintain()} once the application is ready (Liquibase has run) and then on
 * {@code gateway.audit.partition.maintenance-cron} (00:01 on the 1st of each month by default). Several instances may run it at once:
 * creating and dropping partitions is idempotent.
 */
@Component
public class AuditPartitionMaintenance {

    private final AuditPartitions partitions;

    public AuditPartitionMaintenance(AuditPartitions partitions) {
        this.partitions = partitions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        partitions.maintain();
    }

    @Scheduled(cron = "${gateway.audit.partition.maintenance-cron:0 1 0 1 * *}")
    public void scheduled() {
        partitions.maintain();
    }
}
