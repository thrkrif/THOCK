-- 사용: SET @lo, @hi (n 범위), @pmax (products 최대 id). 리뷰 1건/행.
-- 상품 쪽으로 강하게 몰리게(POW 3) 해서 인기 상품은 리뷰가 수천 건, 대부분은 몇 건.
-- 인기 상품이 최신 상품(id 큰 쪽)에 몰리도록 @pmax 에서 거꾸로 셈 → 목록 첫 페이지(id desc)에 걸림.
-- rating 은 0.5 단위(체크 제약: 0.5~5.0).
INSERT INTO thock_product_db.product_reviews
  (created_at, updated_at, product_id, member_id, rating, content)
SELECT created, created,
       @pmax - FLOOR(POW(hp, 3) * (@pmax - 1)),
       4 + MOD(n, 11808),
       (1 + FLOOR(hr * 10)) / 2,
       CONCAT('리뷰 ', n, ' 타건감이 좋고 소음이 적어요. ', REPEAT('만족 ', 10))
FROM (SELECT n,
             CRC32(CONCAT(n, 'p')) / 4294967296 AS hp,
             CRC32(CONCAT(n, 'r')) / 4294967296 AS hr,
             NOW(6) - INTERVAL FLOOR(CRC32(CONCAT(n, 'c')) / 4294967296 * 31536000) SECOND AS created
      FROM perf_tmp.seq WHERE n BETWEEN @lo AND @hi) x;
