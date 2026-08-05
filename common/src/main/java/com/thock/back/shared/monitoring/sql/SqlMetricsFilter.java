package com.thock.back.shared.monitoring.sql;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.sql-metrics", name = "enabled", havingValue = "true")
public class SqlMetricsFilter extends OncePerRequestFilter {

    private final SqlMetricsRecorder metricsRecorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RequestSqlMetricsContext.start(request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestSqlMetricsContext.Snapshot snapshot = RequestSqlMetricsContext.finish();
            Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (snapshot != null && bestMatchingPattern != null) {
                snapshot = new RequestSqlMetricsContext.Snapshot(
                        snapshot.method(),
                        bestMatchingPattern.toString(),
                        snapshot.queryCount(),
                        snapshot.totalQueryTimeMs()
                );
            }
            metricsRecorder.recordRequest(snapshot, response.getStatus());
        }
    }
}
