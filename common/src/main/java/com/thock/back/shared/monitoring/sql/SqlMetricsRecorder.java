package com.thock.back.shared.monitoring.sql;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.sql-metrics", name = "enabled", havingValue = "true")
public class SqlMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public void recordQuery(long elapsedMillis) {
        Timer.builder("service_db_query_time")
                .description("Execution time of each database query")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordRequest(RequestSqlMetricsContext.Snapshot snapshot, int status) {
        if (snapshot == null) {
            return;
        }

        Tags tags = Tags.of(
                "method", snapshot.method(),
                "uri", snapshot.uri(),
                "status", String.valueOf(status)
        );

        DistributionSummary.builder("service_http_db_queries")
                .description("Database queries executed per HTTP request")
                .baseUnit("queries")
                .serviceLevelObjectives(1, 3, 5, 10, 20, 50, 100)
                .tags(tags)
                .register(meterRegistry)
                .record(snapshot.queryCount());

        Timer.builder("service_http_db_time")
                .description("Total database execution time per HTTP request")
                .publishPercentileHistogram()
                .tags(tags)
                .register(meterRegistry)
                .record(snapshot.totalQueryTimeMs(), TimeUnit.MILLISECONDS);
    }
}
