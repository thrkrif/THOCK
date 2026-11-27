-- 사용: SET @lo, @hi (주문 n 범위), @pmax (products 최대 id). 주문당 1~2개 (짝수 n 은 2개).
INSERT INTO thock_market_db.market_order_items
  (created_at, updated_at, cancel_reason_detail, cancel_reason_type, delivered_at, discount_amount,
   fee_amount, payout_amount, payout_rate, price, product_id, product_image_url, product_name,
   quantity, sale_price, seller_id, state, total_price, total_sale_price, order_id)
SELECT o.created_at, o.created_at, NULL,
       IF(s.st = 'CANCELLED', 'CHANGE_OF_MIND', NULL),
       IF(s.st IN ('DELIVERED','CONFIRMED'), LEAST(o.created_at + INTERVAL 3 DAY, NOW(6)), NULL),
       p.price*q.qty - p.sale_price*q.qty,
       FLOOR(p.sale_price*q.qty*0.1), p.sale_price*q.qty - FLOOR(p.sale_price*q.qty*0.1), 0.9,
       p.price, p.id, p.image_url, p.name, q.qty, p.sale_price, p.seller_id, s.st,
       p.price*q.qty, p.sale_price*q.qty, o.id
FROM (SELECT n, k.k,
             1 + MOD(CRC32(CONCAT(n,k.k,'p')), @pmax) AS pid,
             1 + MOD(CRC32(CONCAT(n,k.k,'q')), 3) AS qty_
      FROM perf_tmp.seq
      JOIN (SELECT 1 AS k UNION ALL SELECT 2) k ON k.k = 1 OR MOD(n,2) = 0
      WHERE n BETWEEN @lo AND @hi) x
JOIN thock_market_db.market_orders o ON o.id = 10000000 + x.n
JOIN thock_product_db.products p ON p.id = x.pid
JOIN (SELECT 1 AS dummy) d ON 1=1
JOIN LATERAL (SELECT x.qty_ AS qty) q ON 1=1
JOIN LATERAL (SELECT CASE o.state
                       WHEN 'PARTIALLY_CANCELLED' THEN IF(x.k=1,'CANCELLED','CONFIRMED')
                       WHEN 'PARTIALLY_REFUNDED'  THEN IF(x.k=1,'REFUNDED','CONFIRMED')
                       ELSE o.state END AS st) s ON 1=1;
