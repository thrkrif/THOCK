-- M8: 상품 목록의 리뷰 요약(평균 별점, 리뷰 수) 집계용 커버링 인덱스
-- WHERE product_id IN (...) GROUP BY product_id 의 avg(rating), count(id) 를
-- 테이블 행을 읽지 않고 인덱스만으로 계산한다. (InnoDB 보조 인덱스에는 PK(id)가 포함됨)
CREATE INDEX idx_product_reviews_product_rating ON product_reviews (product_id, rating);
