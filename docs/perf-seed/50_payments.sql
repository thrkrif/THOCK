-- 사용: SET @lo, @hi (주문 n 범위). 더미 주문(id = 10000000 + n) 1건당 결제 1건.
-- 결제 대기(PENDING_PAYMENT) 주문은 결제 행이 없음. order_id 는 주문번호(PERF-n)와 같음.
INSERT INTO thock_payment_db.payment_payments
  (created_at, updated_at, amount, order_id, payment_key, pg_amount, refunded_amount, status, version, buyer_id)
SELECT o.created_at + INTERVAL 1 MINUTE, o.created_at + INTERVAL 1 MINUTE,
       o.total_sale_price, o.order_number,
       IF(MOD(o.id, 2) = 0, CONCAT('tgen_perf_', o.id), NULL),
       IF(MOD(o.id, 2) = 0, o.total_sale_price, 0),
       CASE WHEN o.state IN ('CANCELLED','REFUNDED') THEN o.total_sale_price
            WHEN o.state IN ('PARTIALLY_CANCELLED','PARTIALLY_REFUNDED') THEN FLOOR(o.total_sale_price / 2)
            ELSE 0 END,
       CASE WHEN o.state IN ('CANCELLED','REFUNDED') THEN 'CANCELED'
            WHEN o.state IN ('PARTIALLY_CANCELLED','PARTIALLY_REFUNDED') THEN 'PARTIALLY_CANCELED'
            ELSE 'COMPLETED' END,
       0, o.buyer_id
FROM thock_market_db.market_orders o
WHERE o.id BETWEEN 10000000 + @lo AND 10000000 + @hi
  AND o.state <> 'PENDING_PAYMENT';
