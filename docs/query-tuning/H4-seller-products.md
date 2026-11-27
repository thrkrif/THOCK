# H4. 판매자 상품 목록 `GET /api/v1/products/me` — (seller_id, id) 인덱스

## 한 줄 요약

판매자 상품 목록의 페이징 count 가 매 요청 상품 50만 행을 풀스캔했다. `(seller_id, id)` 인덱스로 count **138ms → 0.2ms**.

## 1. 증상

```
Count: 429  Time=0.20s (83s)   ← baseline 슬로우 로그 4위
  select count(p1_0.id) from products p1_0 where p1_0.seller_id=N

# Query_time: 0.443027  Rows_sent: 1  Rows_examined: 500522  Read_rnd_next: 500523
```

`Page` 응답이라 목록 쿼리와 별도로 전체 건수(count)를 매번 구하는데, 이 count 가 풀스캔이었다.
목록 쿼리 자체(`ORDER BY id DESC LIMIT 10`)는 PK 를 거꾸로 읽다가 10건을 찾으면 멈춰서 빨랐다(0.7ms).

## 2. 원인 분석

```sql
EXPLAIN SELECT count(p1_0.id) FROM products p1_0 WHERE p1_0.seller_id = 100;
```

| type | key | rows | Extra |
|---|---|---|---|
| ALL | NULL | 485,463 | Using where |

`seller_id` 인덱스가 없다. 상품 1,495개인 판매자를 세려고 50만 행을 읽는다.

## 3. 해결 (Flyway `product V6`)

```sql
CREATE INDEX idx_products_seller_id ON products (seller_id, id);
```

- `id` 를 두 번째 컬럼으로 둔 이유: `WHERE seller_id = ? ORDER BY id DESC LIMIT ?` 를 이 인덱스 순서 그대로 읽어 정렬(filesort)이 필요 없다
- count 는 인덱스만 보면 된다(커버링: InnoDB 보조 인덱스에는 PK 가 들어 있음)

## 4. 재측정

| | 전 | 후 |
|---|---|---|
| count (상품 1,495개 판매자) | 138ms | **0.2ms** |
| count (상품 2.9만 개 판매자) | 134ms | **2.9ms** |
| 목록 10건 | 0.7ms | 0.24ms |

```
-> Aggregate: count(p1_0.id)  (actual time=0.32..0.32 rows=1 loops=1)
    -> Covering index lookup on p1_0 using idx_products_seller_id (seller_id=100)  (actual time=0.0993..0.266 rows=1495 loops=1)
```

## 사용한 명령

```sql
EXPLAIN ANALYZE SELECT count(p1_0.id) FROM products p1_0 WHERE p1_0.seller_id = 100;
EXPLAIN SELECT ... FROM products p1_0 WHERE p1_0.seller_id = 100 ORDER BY p1_0.id DESC LIMIT 0, 10;
```
