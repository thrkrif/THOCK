package com.thock.back.market.monitoring.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRequestSqlMetricsContextTest {

    @AfterEach
    void tearDown() {
        MarketRequestSqlMetricsContext.finish();
    }

    @Test
    void countsRepeatedNormalizedSelectStatementsWithinRequest() {
        MarketRequestSqlMetricsContext.start("GET", "/api/v1/orders");

        for (int i = 0; i < 10; i++) {
            MarketRequestSqlMetricsContext.recordQuery(
                    "select oi1_0.order_id, oi1_0.id from order_items oi1_0 where oi1_0.order_id=?"
            );
        }

        MarketRequestSqlMetricsContext.Snapshot snapshot = MarketRequestSqlMetricsContext.finish();

        assertThat(snapshot.selectQueries()).isEqualTo(10);
        assertThat(snapshot.maxRepeatedSelectQueries()).isEqualTo(10);
    }

    @Test
    void doesNotTreatManyDifferentSelectStatementsAsRepeatedSelects() {
        MarketRequestSqlMetricsContext.start("GET", "/api/v1/orders");

        for (int i = 0; i < 15; i++) {
            MarketRequestSqlMetricsContext.recordQuery(
                    "select t" + i + ".id from table_" + i + " t" + i + " where t" + i + ".id=?"
            );
        }

        MarketRequestSqlMetricsContext.Snapshot snapshot = MarketRequestSqlMetricsContext.finish();

        assertThat(snapshot.selectQueries()).isEqualTo(15);
        assertThat(snapshot.maxRepeatedSelectQueries()).isEqualTo(1);
    }

    @Test
    void normalizesWhitespaceAndCaseBeforeCountingRepeatedSelects() {
        MarketRequestSqlMetricsContext.start("GET", "/api/v1/orders");

        MarketRequestSqlMetricsContext.recordQuery("SELECT  o.id  FROM orders o WHERE o.id=?");
        MarketRequestSqlMetricsContext.recordQuery("select o.id from orders o where o.id=?");

        MarketRequestSqlMetricsContext.Snapshot snapshot = MarketRequestSqlMetricsContext.finish();

        assertThat(snapshot.maxRepeatedSelectQueries()).isEqualTo(2);
    }
}
