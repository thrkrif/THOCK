-- H4: 판매자 상품 목록(GET /api/v1/products/me) 조회용
-- WHERE seller_id = ? ORDER BY id DESC LIMIT ? 를 인덱스 순서 그대로 읽고(filesort 없음),
-- 페이징 count(*) 도 이 인덱스 범위만 센다.
CREATE INDEX idx_products_seller_id ON products (seller_id, id);
