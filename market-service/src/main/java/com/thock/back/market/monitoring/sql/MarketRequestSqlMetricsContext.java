package com.thock.back.market.monitoring.sql;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class MarketRequestSqlMetricsContext {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final ThreadLocal<MutableSnapshot> HOLDER = new ThreadLocal<>();

    private MarketRequestSqlMetricsContext() {
    }

    public static void start(String method, String uri) {
        HOLDER.set(new MutableSnapshot(method, uri));
    }

    public static void recordQuery(String sql) {
        MutableSnapshot snapshot = HOLDER.get();
        if (snapshot == null || sql == null) {
            return;
        }

        snapshot.totalQueries++;

        String normalized = normalize(sql);
        if (normalized.startsWith("select")) {
            snapshot.selectQueries++;
            int repeatedCount = snapshot.selectSqlCounts.merge(normalized, 1, Integer::sum);
            snapshot.maxRepeatedSelectQueries = Math.max(snapshot.maxRepeatedSelectQueries, repeatedCount);
        } else if (normalized.startsWith("insert")) {
            snapshot.insertQueries++;
        } else if (normalized.startsWith("update")) {
            snapshot.updateQueries++;
        } else if (normalized.startsWith("delete")) {
            snapshot.deleteQueries++;
        }
    }

    public static void recordQueryTime(long elapsedMillis) {
        MutableSnapshot snapshot = HOLDER.get();
        if (snapshot == null || elapsedMillis < 0) {
            return;
        }
        snapshot.totalQueryTimeMs += elapsedMillis;
    }

    public static Snapshot finish() {
        MutableSnapshot snapshot = HOLDER.get();
        HOLDER.remove();

        if (snapshot == null) {
            return null;
        }

        return new Snapshot(
                snapshot.method,
                snapshot.uri,
                snapshot.totalQueries,
                snapshot.selectQueries,
                snapshot.insertQueries,
                snapshot.updateQueries,
                snapshot.deleteQueries,
                snapshot.totalQueryTimeMs,
                snapshot.maxRepeatedSelectQueries
        );
    }

    private static String normalize(String sql) {
        return WHITESPACE.matcher(sql.strip().toLowerCase()).replaceAll(" ");
    }

    private static final class MutableSnapshot {
        private final String method;
        private final String uri;
        private final Map<String, Integer> selectSqlCounts = new HashMap<>();
        private int totalQueries;
        private int selectQueries;
        private int insertQueries;
        private int updateQueries;
        private int deleteQueries;
        private long totalQueryTimeMs;
        private int maxRepeatedSelectQueries;

        private MutableSnapshot(String method, String uri) {
            this.method = method;
            this.uri = uri;
        }
    }

    public record Snapshot(
            String method,
            String uri,
            int totalQueries,
            int selectQueries,
            int insertQueries,
            int updateQueries,
            int deleteQueries,
            long totalQueryTimeMs,
            int maxRepeatedSelectQueries
    ) {
    }
}
