-- 사용: SET @lo, @hi, @pmax. id = 900000000000000000 + n. order_no 는 PERF-n (주문과 동일).
INSERT INTO thock_settlement_db.sales_log
  (id, created_at, updated_at, confirmed_at, daily_settlement_id, metadata, order_no, payment_amount,
   payment_method, product_id, product_name, product_price, product_quantity, reconciliation_status,
   seller_id, snapshot_at, transaction_type)
SELECT 900000000000000000 + n, snap, snap,
       IF(st='MATCH' AND age > 86400, snap + INTERVAL 1 DAY, NULL),
       IF(st='MATCH' AND age > 259200, 1000000 + FLOOR(age/86400), NULL),
       NULL, CONCAT('PERF-', n), amount,
       ELT(1+FLOOR(hy*3),'CARD','PAY','POINT'),
       1 + MOD(CRC32(CONCAT(n,'p')), @pmax), CONCAT('Dummy Keyboard ', n), amount, 1, st,
       4 + FLOOR(POW(hz,2) * 300), snap, IF(hr<0.05,'REFUND','PAYMENT')
FROM (
  SELECT n, st, age, amount, hy, hz, hr, NOW(6) - INTERVAL FLOOR(age) SECOND AS snap
  FROM (
    SELECT n,
           CASE WHEN r<0.02 THEN 'PENDING' WHEN r<0.03 THEN 'MISMATCH' ELSE 'MATCH' END AS st,
           IF(r<0.02, hb*86400, ha*31536000) AS age,
           (20 + FLOOR(hm*480)) * 1000 AS amount, hy, hz, hr
    FROM (SELECT n, CRC32(CONCAT(n,'t'))/4294967296 AS r, CRC32(CONCAT(n,'a'))/4294967296 AS ha,
                 CRC32(CONCAT(n,'b'))/4294967296 AS hb, CRC32(CONCAT(n,'m'))/4294967296 AS hm,
                 CRC32(CONCAT(n,'y'))/4294967296 AS hy, CRC32(CONCAT(n,'z'))/4294967296 AS hz,
                 CRC32(CONCAT(n,'r'))/4294967296 AS hr
          FROM perf_tmp.seq WHERE n BETWEEN @lo AND @hi) a
  ) b
) x;
