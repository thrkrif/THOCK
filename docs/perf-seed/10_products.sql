-- 사용: SET @lo, @hi (n 범위). products 는 auto_increment id.
INSERT INTO thock_product_db.products
  (created_at, updated_at, category, description, detail, image_url, name,
   price, sale_price, seller_id, state, stock, view_count, reserved_stock)
SELECT created, created, 'KEYBOARD',
       CONCAT('Dummy keyboard ', n, ' ', REPEAT('lorem ipsum ', 20)),
       JSON_OBJECT('seed', n),
       CONCAT('https://img.example.com/products/', n, '.jpg'),
       CONCAT(ELT(1+FLOOR(hb*8),'Keychron','Logitech','Leopold','Ducky','Varmilo','Akko','Royal Kludge','Razer'), ' ',
              ELT(1+FLOOR(hs*6),'Q1','K2','G Pro','Mini','Pro','TKL'), ' M', n, ' ',
              ELT(1+FLOOR(hw*5),'Red','Brown','Blue','Silent','Linear'), ' ',
              ELT(1+FLOOR(hl*4),'60%','75%','TKL','Full')),
       (30 + FLOOR(hp*270)) * 1000,
       FLOOR((30 + FLOOR(hp*270)) * 1000 * 0.9),
       4 + FLOOR(POW(hz,2) * 300),                 -- 판매자 300명, 일부에 몰림
       ELT(1 + (ht>0.9) + (ht>0.95), 'ON_SALE','SOLDOUT','STOPPED'),
       FLOOR(hk*500),
       FLOOR(POW(hv,3)*100000),
       0
FROM (SELECT n,
             NOW(6) - INTERVAL FLOOR(CRC32(CONCAT(n,'c'))/4294967296*63072000) SECOND AS created,
             CRC32(CONCAT(n,'b'))/4294967296 AS hb, CRC32(CONCAT(n,'s'))/4294967296 AS hs,
             CRC32(CONCAT(n,'w'))/4294967296 AS hw, CRC32(CONCAT(n,'l'))/4294967296 AS hl,
             CRC32(CONCAT(n,'p'))/4294967296 AS hp, CRC32(CONCAT(n,'z'))/4294967296 AS hz,
             CRC32(CONCAT(n,'t'))/4294967296 AS ht, CRC32(CONCAT(n,'k'))/4294967296 AS hk,
             CRC32(CONCAT(n,'v'))/4294967296 AS hv
      FROM perf_tmp.seq WHERE n BETWEEN @lo AND @hi) x;
