package com.thock.back.shared.monitoring.sql;

import java.util.concurrent.atomic.AtomicInteger;

public final class RequestSqlMetricsContext {

    private static final ThreadLocal<MutableSnapshot> HOLDER = new ThreadLocal<>();

    private RequestSqlMetricsContext() {
    }

    public static void start(String method, String uri) {
        HOLDER.set(new MutableSnapshot(method, uri));
    }

    public static void recordQuery(long elapsedMillis) {
        MutableSnapshot snapshot = HOLDER.get();
        if (snapshot == null || elapsedMillis < 0) {
            return;
        }
        snapshot.queryCount.incrementAndGet();
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
                snapshot.queryCount.get(),
                snapshot.totalQueryTimeMs
        );
    }

    private static final class MutableSnapshot {
        private final String method;
        private final String uri;
        private final AtomicInteger queryCount = new AtomicInteger();
        private long totalQueryTimeMs;

        private MutableSnapshot(String method, String uri) {
            this.method = method;
            this.uri = uri;
        }
    }

    public record Snapshot(String method, String uri, int queryCount, long totalQueryTimeMs) {
    }
}
