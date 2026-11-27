# M3. 자동 구매 확정 배치 / M4. 판매자 배송 목록

## M3. 자동 구매 확정 배치 — (state, delivered_at) 인덱스

### 증상과 원인

매일 새벽 3시 `OrderAutoConfirmScheduler` 가 배송 완료 후 7일 지난 주문상품을 찾는다.

```sql
EXPLAIN SELECT ... FROM market_order_items oi1_0
WHERE oi1_0.state = 'DELIVERED' AND oi1_0.delivered_at < now() - INTERVAL 7 DAY;
```

| type | key | rows | Extra |
|---|---|---|---|
| ALL | NULL | 1,190,732 | Using where |

기존 인덱스 `idx_seller_state (seller_id, state)` 는 **선두 컬럼이 `seller_id`** 라 `seller_id` 조건이 없는 이 쿼리에 쓸 수 없다.
복합 인덱스는 앞 컬럼부터 순서대로만 쓸 수 있다(왼쪽 접두어 규칙).

### 해결 (Flyway `market V5`)

```sql
CREATE INDEX idx_order_items_state_delivered ON market_order_items (state, delivered_at);
```

### 결과

| | 전 | 후 |
|---|---|---|
| 서버 실행 시간 | 379ms | 163ms |
| 계획 | `ALL`, 119만 행 | `range` (`state = 'DELIVERED' AND delivered_at < ?`), 7만 행 |

이 측정은 대상이 7만 건(테스트 데이터에 쌓인 2주치)이라 행을 읽어 오는 시간이 대부분이다.
배치가 매일 돌면 평상시 대상은 하루치뿐이라 차이가 더 커진다(H1 정리 스케줄러와 같은 구조).

## M4. 판매자 배송 목록 `GET /api/v1/shipments/me` — 인덱스 + fetch join

### 증상

```
# Query_time: 1.362751  Rows_sent: 2758  Rows_examined: 944039  Read_rnd_next: 941282  Sort_rows: 2758
select s1_0.id, ... from market_shipments s1_0 where s1_0.seller_id=100 order by s1_0.created_at desc;
```

그리고 Prometheus 지표 `market_http_db_select_queries` 로 보니 **요청 1번에 SELECT 가 57번** 나갔다(41요청 / 2,337 SELECT).

### 원인 1: 인덱스 없음

```sql
EXPLAIN SELECT ... FROM market_shipments s1_0 WHERE s1_0.seller_id = 100 ORDER BY s1_0.created_at DESC;
```

| type | key | rows | Extra |
|---|---|---|---|
| ALL | NULL | 933,759 | Using where; Using filesort |

### 원인 2: 응답 변환 중 지연 로딩

```java
public static ShipmentResponse from(Shipment shipment) {
    var item = shipment.getOrderItem();   // LAZY → 조회
    var order = item.getOrder();          // LAZY → 조회
```

`default_batch_fetch_size: 100` 덕분에 1건씩(N+1)은 아니지만, 배송 2,758건이면 주문상품 28번 + 주문 28번이 추가로 나간다.
구매자용 조회(`findByBuyerId`)는 이미 `join fetch` 였는데 판매자용만 빠져 있었다.

### 해결

```sql
-- Flyway market V6
CREATE INDEX idx_shipments_seller_created ON market_shipments (seller_id, created_at);
```

```java
// ShipmentRepository: 메서드 이름 기반 쿼리 → fetch join
@Query("select s from Shipment s join fetch s.orderItem oi join fetch oi.order o where s.sellerId = :sellerId order by s.createdAt desc")
List<Shipment> findBySellerIdOrderByCreatedAtDesc(@Param("sellerId") Long sellerId);
```

### 결과

| | 전 | 후 |
|---|---|---|
| 배송 조회 쿼리 (조인 포함) | 298ms (`ALL` + filesort) | 72ms (`ref`, Backward index scan, PK 조인) |
| 요청당 SELECT 수 | 57 | **1** |
| API p95 (k6) | 955ms | **216ms** |

```
-> Nested loop inner join  (actual time=14.9..248 rows=2758 loops=1)
    -> Nested loop inner join
        -> Index lookup on s1_0 using idx_shipments_seller_created (seller_id=100) (reverse)  (rows=2758)
        -> Single-row index lookup on oi1_0 using PRIMARY (id=s1_0.order_item_id)  (loops=2758)
    -> Single-row index lookup on o1_0 using PRIMARY (id=oi1_0.order_id)  (loops=2758)
```

### 남은 것

판매자 배송이 수만 건이면 여전히 한 번에 다 내려준다(판매자 4번은 5.4만 건). 관리자용 `findAllByOrderByCreatedAtDesc` 는 94만 건 전체다.
페이징이 필요하지만 응답 형식이 바뀌어 보류했다([deferred.md](deferred.md)).

## 사용한 명령

```sql
EXPLAIN SELECT ... FROM market_order_items oi1_0 WHERE oi1_0.state = 'DELIVERED' AND oi1_0.delivered_at < now() - INTERVAL 7 DAY;
EXPLAIN ANALYZE SELECT ... FROM market_shipments s1_0 JOIN market_order_items oi1_0 ON ... JOIN market_orders o1_0 ON ...
WHERE s1_0.seller_id = 100 ORDER BY s1_0.created_at DESC;
```

```bash
# 요청당 SELECT 수 (market-service 의 SQL 메트릭)
curl -s http://localhost:8083/actuator/prometheus | grep market_http_db_select_queries | grep shipments
```
