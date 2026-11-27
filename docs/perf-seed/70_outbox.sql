-- 사용: SET @lo, @hi (n 범위). market outbox_event 더미.
-- 하루 약 6만 건 발행, SENT 는 보관 7일 + 하루치(정리 배치 직전) = 최근 8일에 분포.
-- 0.4%는 FAILED: 그중 95%는 재시도 한도(5회)를 넘겨 계속 남아 있는 행(최근 90일에 분포).
INSERT INTO thock_market_db.outbox_event
  (aggregate_id, aggregate_type, created_at, event_type, last_error_message, payload,
   processing_started_at, retry_count, sent_at, status, topic, version)
SELECT CONCAT('PERF-', n), 'Order', created,
       ELT(1 + MOD(n, 3), 'MarketOrderPaymentRequestedEvent', 'MarketOrderPaymentCompletedEvent', 'MarketOrderCanceledEvent'),
       IF(failed, 'org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s)', NULL),
       CONCAT('{"orderNumber":"PERF-', n, '","buyerId":', 4 + MOD(n, 11808), ',"amount":', (20 + MOD(n, 480)) * 1000, ',"items":[{"productId":', 1 + MOD(n, 500000), ',"quantity":1}]}'),
       NULL,
       IF(failed, IF(MOD(n, 20) = 0, 1 + MOD(n, 4), 5), 0),
       IF(failed, NULL, created + INTERVAL (200 * (1 + MOD(n, 50))) MICROSECOND),
       IF(failed, 'FAILED', 'SENT'),
       ELT(1 + MOD(n, 3), 'market.order.payment.requested', 'market.order.payment.completed', 'market.order.canceled'),
       0
FROM (SELECT n,
             CRC32(CONCAT(n, 'f')) / 4294967296 < 0.004 AS failed,
             NOW(6) - INTERVAL FLOOR(CRC32(CONCAT(n, 'c')) / 4294967296
                       * IF(CRC32(CONCAT(n, 'f')) / 4294967296 < 0.004, 7776000, 691200)) SECOND AS created
      FROM perf_tmp.seq WHERE n BETWEEN @lo AND @hi) x;
