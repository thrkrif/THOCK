# M2. 내 주문 목록 — (buyer_id, created_at) 인덱스: 효과 없어 적용하지 않음

## 한 줄 요약

분석 문서의 안대로 `(buyer_id, created_at)` 인덱스를 만들어 쟀지만 빨라지지 않았고, 주문이 많은 구매자는 오히려 느려졌다(159 → 222ms). 인덱스를 되돌렸다.

## 1. 쿼리

```java
@Query("""
        select distinct o from Order o
        left join fetch o.items
        where o.buyer.id = :buyerId
        order by o.createdAt desc
        """)
List<Order> findByBuyerIdWithItemsOrderByCreatedAtDesc(@Param("buyerId") Long buyerId);
```

## 2. 측정

| 구매자 | 주문 수 | 전 | 후 (인덱스 추가) | 후 계획 |
|---|---|---|---|---|
| 100 | 475 | 3.9ms | 3.1ms | `ref`, Backward index scan; **Using temporary** |
| 4 | 9,213 | 159ms | **222ms** | `range`, Using MRR; **Using temporary** |

전 계획: `ref` (`uk_orders_buyer_id_idempotency_key`), `Using temporary; Using filesort`

## 3. 왜 효과가 없었나

- 정렬(`created_at`)은 인덱스로 해결됐지만(`Using filesort` 사라짐), `DISTINCT` 때문에 **주문 × 주문상품 조인 결과를 임시 테이블에 담는 비용**이 그대로다
- 주문이 많으면 옵티마이저가 MRR(정렬된 PK 순서로 모아 읽기)을 골랐는데 이번 데이터에서는 더 느렸다
- 이 API 의 진짜 비용은 "구매자의 전체 주문을 페이징 없이 한 번에" 읽는 것 자체다

## 4. 결정

- 효과가 없는 인덱스는 쓰기 비용(주문 생성/상태 변경마다 인덱스 갱신)만 늘리므로 추가하지 않았다
- 근본 해결은 페이징(주문만 `LIMIT` 으로 끊고 주문상품은 `default_batch_fetch_size=100` 으로 가져오기)인데,
  응답이 `List` → `Page` 로 바뀌어 프론트 수정이 필요해 보류했다([deferred.md](deferred.md))

## 사용한 명령

```sql
CREATE INDEX idx_orders_buyer_created ON market_orders (buyer_id, created_at);
EXPLAIN SELECT DISTINCT ... FROM market_orders o1_0 LEFT JOIN market_order_items i1_0 ON o1_0.id = i1_0.order_id
WHERE o1_0.buyer_id = 4 ORDER BY o1_0.created_at DESC;
DROP INDEX idx_orders_buyer_created ON market_orders;   -- 효과 없어 삭제
```
