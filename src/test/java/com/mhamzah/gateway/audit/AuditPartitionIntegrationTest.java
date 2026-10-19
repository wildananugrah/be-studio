package com.mhamzah.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.TestcontainersConfiguration;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.masking.Masker;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The partitioned audit tables on the real database: partitions ready at startup, a row with no partition yet
 * (PostgreSQL) gets one when it is written, and partitions older than the retention are dropped with their rows.
 */
@SpringBootTest(properties = {"gateway.audit.partition.retention=365d", "gateway.admin.token=t"})
@Import(TestcontainersConfiguration.class)
class AuditPartitionIntegrationTest {

    @Autowired
    AuditPartitions partitions;

    @Autowired
    GatewayProperties properties;

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    Masker masker;

    @Autowired
    JdbcTemplate jdbc;

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " " + where, Integer.class, args);
    }

    @Test
    void partitionsAreReadyAtStartup() {
        if (TestcontainersConfiguration.isOracle()) {
            assertThat(jdbc.queryForObject("SELECT interval FROM user_part_tables WHERE table_name = 'GW_AUDIT_TRANSACTION'",
                    String.class)).contains(properties.audit().partition().interval().name());
            return;
        }
        LocalDateTime first = partitions.floor(LocalDateTime.now());
        LocalDateTime second = partitions.next(first);
        assertThat(partitions.pgRanges("gw_audit_transaction")).extracting(AuditPartitions.Range::from)
                .contains(first, second, partitions.next(second), partitions.next(partitions.next(second)));
        assertThat(partitions.pgRanges("gw_audit_step")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void oldRowsGetAPartitionAndAreDroppedAfterTheRetention() {
        AuditWriter writer = new AuditWriter(dataSource, txManager, properties.db(), masker, true).withPartitions(partitions);
        Instant old = LocalDateTime.now().minusYears(3).atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS);
        writer.write(List.of(new AuditRecord("partition-old-1", "F", "GET", "/x", 200, null, null, null, null, old, 5,
                List.of(new AuditRecord.StepRecord("s1", "CORE", "GET", "http://core/x", 200, "SUCCESS", null, null, old, 3L)))));
        writer.write(List.of(new AuditRecord("partition-new-1", "F", "GET", "/x", 200, null, null, null, null,
                Instant.now(), 5, List.of(new AuditRecord.StepRecord("s1", "CORE", "GET", "http://core/x", 200, "SUCCESS",
                        null, null, Instant.now(), 3L)))));

        assertThat(count("gw_audit_transaction", "WHERE correlation_id = ?", "partition-old-1")).isEqualTo(1);
        assertThat(count("gw_audit_step s JOIN gw_audit_transaction t ON t.id = s.transaction_id"
                + " AND t.started_at = s.transaction_started_at", "WHERE t.correlation_id = ?", "partition-old-1")).isEqualTo(1);

        partitions.maintain();

        assertThat(count("gw_audit_transaction", "WHERE correlation_id = ?", "partition-old-1")).isZero();
        assertThat(count("gw_audit_step", "WHERE transaction_started_at < ?", java.sql.Timestamp.from(old.plusSeconds(1))))
                .isZero();
        assertThat(count("gw_audit_transaction", "WHERE correlation_id = ?", "partition-new-1")).isEqualTo(1);
        assertThat(count("gw_audit_step s JOIN gw_audit_transaction t ON t.id = s.transaction_id",
                "WHERE t.correlation_id = ?", "partition-new-1")).isEqualTo(1);
    }
}
