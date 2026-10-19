package com.mhamzah.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.audit.AuditPartitions.Range;
import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.config.GatewayProperties.PartitionInterval;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The period arithmetic of the audit partitions; the SQL is covered by AuditPartitionIntegrationTest. */
class AuditPartitionsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 14, 30);

    private static AuditPartitions partitions(PartitionInterval interval) {
        GatewayProperties.Db db = Binder.get(new org.springframework.core.env.StandardEnvironment())
                .bindOrCreate("gateway", Bindable.of(GatewayProperties.class)).db();
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new AuditPartitions(new SimpleDriverDataSource(), db,
                new GatewayProperties.Partition(interval, 2, Period.ofMonths(6), "0 5 * * * *"), clock);
    }

    private static LocalDateTime d(int y, int m, int day) {
        return LocalDateTime.of(y, m, day, 0, 0);
    }

    @Test
    void periodsStartAtMidnightOrTheFirstOfTheMonth() {
        assertThat(partitions(PartitionInterval.MONTH).floor(NOW)).isEqualTo(d(2026, 10, 1));
        assertThat(partitions(PartitionInterval.MONTH).next(d(2026, 12, 1))).isEqualTo(d(2027, 1, 1));
        assertThat(partitions(PartitionInterval.DAY).floor(NOW)).isEqualTo(d(2026, 10, 19));
        assertThat(partitions(PartitionInterval.DAY).next(d(2026, 10, 31))).isEqualTo(d(2026, 11, 1));
    }

    @Test
    void missingSkipsCoveredPeriodsAndStopsAtTheNextPartition() {
        AuditPartitions daily = partitions(PartitionInterval.DAY);
        // a monthly partition exists (the interval was MONTH before): days inside it are covered
        List<Range> existing = List.of(new Range("t_p20261001", d(2026, 10, 1), d(2026, 11, 1)));
        assertThat(daily.missing("T", existing, List.of(d(2026, 10, 19), d(2026, 11, 1))))
                .containsExactly(new Range("t_p20261101", d(2026, 11, 1), d(2026, 11, 2)));

        // switching to MONTH with daily partitions ahead: the month ends where the first daily one starts
        AuditPartitions monthly = partitions(PartitionInterval.MONTH);
        existing = List.of(new Range("t_p20261120", d(2026, 11, 20), d(2026, 11, 21)));
        assertThat(monthly.missing("T", existing, List.of(d(2026, 11, 1), d(2026, 12, 1))))
                .containsExactly(new Range("t_p20261101", d(2026, 11, 1), d(2026, 11, 20)),
                        new Range("t_p20261201", d(2026, 12, 1), d(2027, 1, 1)));
    }

    @Test
    void expiredAreThoseEndingBeforeTheCutoff() {
        List<Range> ranges = List.of(new Range("a", d(2026, 3, 1), d(2026, 4, 1)),
                new Range("b", d(2026, 4, 1), d(2026, 5, 1)), new Range("c", d(2026, 5, 1), d(2026, 6, 1)));
        // six months before 2026-10-19 14:30: April is still needed for the 19th onwards
        assertThat(AuditPartitions.expired(ranges, NOW.minusMonths(6))).extracting(Range::name).containsExactly("a");
    }
}
