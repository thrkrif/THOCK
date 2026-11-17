-- 사용: SET @lo, @hi. id = 10000000 + n. 구매자는 기존 market_members(4..11811) 중 일부에 몰림.
INSERT INTO thock_market_db.market_orders
  (id, created_at, updated_at, base_address, cancel_date, detail_address, order_number,
   payment_date, request_payment_date, state, total_discount_amount, total_price,
   total_sale_price, zip_code, buyer_id, version, idempotency_key, coupon_id, coupon_discount_amount)
SELECT 10000000 + n, created, created, '서울시 강남구 테헤란로',
       IF(state IN ('CANCELLED','PARTIALLY_CANCELLED'), created + INTERVAL 1 HOUR, NULL),
       CONCAT(MOD(n,1000), '호'), CONCAT('PERF-', n),
       IF(state IN ('PENDING_PAYMENT','CANCELLED'), NULL, created + INTERVAL 1 MINUTE),
       created + INTERVAL IF(state='PENDING_PAYMENT',0,10) SECOND,
       state,
       total - FLOOR(total*0.95), total, FLOOR(total*0.95), '06234',
       4 + FLOOR(POW(hu,2) * 11808), 0, NULL, NULL, 0
FROM (
  SELECT n, state, hu, total,
         NOW(6) - INTERVAL FLOOR(ha * IF(state IN ('PENDING_PAYMENT','PAYMENT_COMPLETED','PREPARING','SHIPPING','DELIVERED'),1209600,31536000)) SECOND AS created
  FROM (
    SELECT n,
           CASE WHEN r<0.71 THEN 'CONFIRMED' WHEN r<0.74 THEN 'DELIVERED' WHEN r<0.77 THEN 'SHIPPING'
                WHEN r<0.80 THEN 'PREPARING' WHEN r<0.84 THEN 'PAYMENT_COMPLETED' WHEN r<0.85 THEN 'PENDING_PAYMENT'
                WHEN r<0.93 THEN 'CANCELLED' WHEN r<0.95 THEN 'PARTIALLY_CANCELLED' WHEN r<0.98 THEN 'REFUNDED'
                ELSE 'PARTIALLY_REFUNDED' END AS state,
           hu, ha, (20 + FLOOR(hm*480)) * 1000 AS total
    FROM (SELECT n, CRC32(CONCAT(n,'t'))/4294967296 AS r, CRC32(CONCAT(n,'u'))/4294967296 AS hu,
                 CRC32(CONCAT(n,'a'))/4294967296 AS ha, CRC32(CONCAT(n,'m'))/4294967296 AS hm
          FROM perf_tmp.seq WHERE n BETWEEN @lo AND @hi) a
  ) b
) x;
