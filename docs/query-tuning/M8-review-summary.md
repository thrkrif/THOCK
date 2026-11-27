# M8. 리뷰 요약 집계 — (product_id, rating) 커버링 인덱스

## 한 줄 요약

상품 목록을 볼 때마다 프론트가 부르는 리뷰 요약 API 가 리뷰 수만 건을 테이블에서 다시 읽었다. 커버링 인덱스로 쿼리 **217ms → 4.3ms**, API p95 **1,153ms → 80ms**.

## 1. 증상

baseline 슬로우 로그 2위:

```
Count: 220  Time=0.46s (100s)
  select pr1_0.product_id,avg(pr1_0.rating),count(pr1_0.id) from product_reviews pr1_0 where pr1_0.product_id in (N,...) group by pr1_0.product_id

# Query_time: 0.808782  Rows_sent: 12  Rows_examined: 26767  Read_key: 12  Read_next: 26767
```

프론트 `HomePage` 는 상품 목록(12건)을 받은 뒤 그 상품들의 리뷰 요약을 `GET /api/v1/products/reviews/summaries?productIds=…` 로 한 번 더 요청한다.
최신 상품에 리뷰가 몰려 있어(1위 상품 11,684건) 첫 페이지 12개 상품의 리뷰가 2.7만 건이다.

## 2. 원인 분석

```
-> Group aggregate: avg(pr1_0.rating), count(pr1_0.id)  (actual time=318..2461 rows=12 loops=1)
    -> Index range scan on pr1_0 using idx_product_reviews_product_created over (product_id = 624839) OR (...)
```

- 기존 인덱스 `(product_id, created_at, id)` 로 상품별 범위는 찾는다
- 하지만 `rating` 이 인덱스에 없어서 **리뷰 2.7만 건마다 테이블 행을 다시 읽는다**(Extra 에 `Using index` 가 없음)

## 3. 해결 (Flyway `product V8`)

```sql
CREATE INDEX idx_product_reviews_product_rating ON product_reviews (product_id, rating);
```

`avg(rating)`, `count(id)` 에 필요한 값(`product_id`, `rating`, 그리고 보조 인덱스에 자동으로 들어 있는 PK `id`)이 모두 인덱스에 있다 → **커버링 인덱스**.
기존 `(product_id, created_at, id)` 는 리뷰 목록 페이징(최신순)에 쓰이므로 그대로 둔다.

## 4. 결과

```
-> Group aggregate: avg(pr1_0.rating), count(pr1_0.id)  (actual time=0.301..7.03 rows=12 loops=1)
    -> Covering index range scan on pr1_0 using idx_product_reviews_product_rating over (product_id = 624839) OR (...)
```

| | 전 | 후 |
|---|---|---|
| 집계 쿼리 (상품 12개, 리뷰 2.7만 건) | 217ms | **4.3ms** |
| 계획 | `range`, Using index condition (행 다시 읽음) | `range`, **Using index** (인덱스만) |

같은 2.7만 건을 읽지만 행 데이터(리뷰 본문 1,000자 포함)를 읽지 않아 50배 빨라졌다.

## 5. 더 줄이려면

리뷰 수 자체에 비례하는 비용은 남는다. 리뷰가 훨씬 많아지면 `products` 에 평균/개수를 비정규화해 리뷰 작성/삭제 때 갱신하는 방식이 필요하다.

## 사용한 명령

```sql
EXPLAIN ANALYZE SELECT pr1_0.product_id, avg(pr1_0.rating), count(pr1_0.id) FROM product_reviews pr1_0
WHERE pr1_0.product_id IN (624839, ..., 624850) GROUP BY pr1_0.product_id;
SELECT product_id, COUNT(*) c FROM product_reviews GROUP BY product_id ORDER BY c DESC LIMIT 3;   -- 리뷰 쏠림 확인
```
