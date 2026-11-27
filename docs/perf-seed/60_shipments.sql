-- 사용: SET @lo, @hi (market_order_items.id 범위). 배송이 시작된 주문상품(SHIPPING/DELIVERED/CONFIRMED)마다 배송 1건.
-- seller_id 는 주문상품의 판매자(약 300명, 일부에 몰림)를 그대로 사용.
INSERT INTO thock_market_db.market_shipments
  (created_at, updated_at, order_item_id, seller_id, carrier, tracking_number, status, shipped_at, delivered_at, version)
SELECT i.created_at + INTERVAL 1 DAY, i.created_at + INTERVAL 1 DAY, i.id, i.seller_id,
       ELT(1 + MOD(i.id, 3), 'CJ', 'HANJIN', 'LOTTE'), CONCAT('PERF', LPAD(i.id, 12, '0')),
       IF(i.state = 'SHIPPING', 'IN_TRANSIT', 'DELIVERED'),
       i.created_at + INTERVAL 1 DAY,
       IF(i.state = 'SHIPPING', NULL, i.delivered_at),
       0
FROM thock_market_db.market_order_items i
WHERE i.id BETWEEN @lo AND @hi
  AND i.state IN ('SHIPPING', 'DELIVERED', 'CONFIRMED');
