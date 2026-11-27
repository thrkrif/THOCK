# 쿼리 성능 정적 분석 결과

코드와 DDL만 읽고 내린 추론입니다(작성 당시). 실제로 서버를 띄워 슬로우 로그와 EXPLAIN 으로 검증하고 개선한 결과는 [query-tuning/README.md](query-tuning/README.md)에 있습니다.
추론과 실측이 달랐던 항목(H3, M2, M5)은 각 문서에 이유를 적었습니다.

## 전제

- **DB와 쿼리 방식**: MySQL 8 / InnoDB. 쿼리는 전부 Spring Data JPA(파생 쿼리, `@Query`, 네이티브 2건)이고 QueryDSL과 `JdbcTemplate`은 없습니다.
- **FK 인덱스는 가정**: FK 컬럼(`buyer_id`, `order_id`, `wallet_id` 등)은 InnoDB가 자동으로 만든 인덱스가 있다고 봤습니다. 실제 확인은 `SHOW INDEX`로 해야 합니다.
- **테이블 크기를 모름**: "풀스캔"은 데이터가 쌓였을 때 기준입니다. 행이 거의 없으면 옵티마이저가 어차피 스캔을 고릅니다.
- **스키마 관리가 서비스마다 다름**
  - market, product, member: Flyway 사용. market은 local 프로파일에서 Flyway가 꺼져 있음
  - payment, settlement: 마이그레이션 파일 없음. 기본 `ddl-auto: update`, prod는 `validate`

## 진행 현황

처리가 끝나면(적용 + 효과 확인) 체크합니다. 항목 상세는 아래 표 참고.

**높음**

- [x] H1 `market_orders.state` 인덱스 + 메트릭 `GROUP BY` 통합 → [H1](query-tuning/H1-order-state.md)
- [x] H2 `payment_payments.order_id` 인덱스 (UNIQUE 는 중복 데이터 발견으로 보류) → [H2](query-tuning/H2-payment-order-id.md)
- [x] H3 상품명 검색 `LIKE '%kw%'` 개선 (FULLTEXT 는 측정 후 기각, 이름 인덱스 + 결과 건수별 조회로 변경) → [H3](query-tuning/H3-product-search.md)
- [x] H4 `products.seller_id` 인덱스 → [H4](query-tuning/H4-seller-products.md)

**중간**

- [x] M1 상품 목록 페이징/정렬 제한 (count 비용은 남음) → [M1](query-tuning/M1-product-paging.md)
- [ ] M2 내 주문 목록 페이징 + `(buyer_id, created_at)` 인덱스 — 인덱스는 측정 결과 효과 없어 미적용, 페이징은 응답 형식 변경이라 보류 → [M2](query-tuning/M2-my-orders.md)
- [x] M3 `market_order_items (state, delivered_at)` 인덱스 → [M3](query-tuning/M3-M4-orders-batch-and-shipments.md)
- [x] M4 `market_shipments (seller_id, created_at)` 인덱스 + fetch join (페이징은 보류) → [M4](query-tuning/M3-M4-orders-batch-and-shipments.md)
- [x] M5 Outbox 쿼리 정리 — count 통합만 적용. 정리 DELETE 변경은 효과 없어 되돌림, `DEAD` 상태는 보류 → [M5](query-tuning/M5-outbox.md)
- [x] M6 `sales_log` 정산 후보 조회 인덱스 → [M6](query-tuning/M6-M7-settlement.md)
- [x] M7 `RunReconciliationUseCase` N+1 제거 → [M7](query-tuning/M6-M7-settlement.md)
- [x] M8 리뷰 요약 집계 커버링 인덱스 → [M8](query-tuning/M8-review-summary.md)
- [ ] M9 무제한 목록 조회 페이징 — 응답 형식 변경이라 보류 → [deferred](query-tuning/deferred.md)

**낮음**

- [ ] 쓰이지 않는 인덱스와 중복 인덱스 삭제
- [ ] 호출 안 되는 메서드 삭제
- [ ] `existsByAggregateIdAndEventTypeAndStatus` 인덱스 조정 (쓰게 될 경우)
- [ ] `RefreshToken` `(member_id, revoked_at)` 인덱스
- [ ] `PESSIMISTIC_WRITE` + `join fetch` 잠금 범위 확인
- [ ] 전역 설정 검토 (`open-in-view`, Hikari 풀, 배치 insert)

## 높음

| # | 문제 | 근거 | 수정안 |
|---|---|---|---|
| H1 | `market_orders.state`에 인덱스가 없음 | `MarketMetricsCollector`가 10초마다 `countByState`를 `OrderState` 11개 값에 대해 각각 실행 → 10초마다 `market_orders` 풀스캔 11번. 여기에 `OrderTimeoutScheduler`(60초마다, `state` + `request_payment_date <`)가 같은 테이블을 스캔 | `CREATE INDEX idx_orders_state_request_payment ON market_orders (state, request_payment_date);`<br>`CREATE INDEX idx_orders_state_cancel_date ON market_orders (state, cancel_date);`<br>메트릭은 `GROUP BY state` 쿼리 1개로 변경 권장 |
| H2 | `payment_payments.order_id`에 인덱스가 없음 | `findByOrderId`가 결제 확정/환불 경로(`PaymentConfirmAndRefundUseCase`에서 6회 안팎)에서 호출됨. VARCHAR 컬럼 풀스캔 | `ALTER TABLE payment_payments ADD UNIQUE INDEX uk_payment_order_id (order_id);`<br>주문 1건당 결제가 1건이 아니면 `UNIQUE` 제외 |
| H3 | 상품명 검색이 `LIKE '%kw%'` | `findByNameContaining`은 인덱스를 못 탐. `Page`라서 `count(*)`도 매번 풀스캔. 드문 키워드는 `ORDER BY id DESC LIMIT`도 테이블 끝까지 훑음 | `ALTER TABLE products ADD FULLTEXT INDEX ft_products_name (name) WITH PARSER ngram;` 후 `MATCH(name) AGAINST(? IN BOOLEAN MODE)` 네이티브 쿼리 사용<br>대안: `Slice`로 count 생략, 또는 prefix 검색(`LIKE 'kw%'` + `name` 인덱스) |
| H4 | `products.seller_id`에 인덱스가 없음 | `findBySellerId`(`GET /me`)가 풀스캔, `count`도 풀스캔 | `CREATE INDEX idx_products_seller_id ON products (seller_id, id);` |

## 중간

| # | 문제 | 수정안 |
|---|---|---|
| M1 | 상품 목록의 페이징과 정렬이 무방비. `category`는 enum 값이 `KEYBOARD` 하나뿐이라 `(category, id)` 인덱스가 사실상 전 테이블. `page`, `size`, `sort`가 전부 사용자 입력이고 `max-page-size` 설정이 없어 기본 상한 2000 적용. `sort=price` 같은 요청은 전 행 filesort | `spring.data.web.pageable.max-page-size` 설정, 정렬 컬럼 화이트리스트, `Slice` 또는 keyset 페이징 도입 |
| M2 | 내 주문 목록(`findByBuyerIdWithItems…`)이 `join fetch` + `distinct`에 페이징 없음. 구매자의 전체 주문을 읽고 `created_at` 정렬은 filesort | `CREATE INDEX idx_orders_buyer_created ON market_orders (buyer_id, created_at);`<br>쿼리는 주문만 페이징하고 items는 `default_batch_fetch_size=100`으로 가져오게 변경 |
| M3 | `OrderItem.findByStateAndDeliveredAtBefore`: 기존 `idx_seller_state (seller_id, state)`는 선두 컬럼이 달라 못 씀. 매일 새벽 3시 풀스캔 | `CREATE INDEX idx_order_items_state_delivered ON market_order_items (state, delivered_at);` |
| M4 | `Shipment.findBySellerIdOrderByCreatedAtDesc`: `seller_id` 인덱스 없고 페이징도 없음. 관리자용 `findAllByOrderByCreatedAtDesc`도 전체 조회 | `CREATE INDEX idx_shipments_seller_created ON market_shipments (seller_id, created_at);` + 페이징 도입 |
| M5 | Outbox 쿼리: `deleteSentEventsBefore`는 `(status, created_at)`만 타서 SENT 전체를 훑음(보관 7일치). `findFailedEventsForRetry`는 10초마다 FAILED를 전부 읽음(재시도 한도를 넘긴 행이 계속 남음). `countByStatus`는 10초마다 4번 실행 | `CREATE INDEX idx_outbox_status_sent ON OUTBOX_EVENT (status, sent_at);`<br>재시도 소진 건을 `DEAD` 같은 별도 상태로 옮기고, count는 `GROUP BY` 1회로 합침 |
| M6 | `SalesLog` 정산 후보 조회: `findSettlementCandidates`는 `idx_sales_log_status` 하나만 타서 지금까지의 MATCH 행을 전부 읽고, `confirmed_at`/`daily_settlement_id`는 행을 읽은 뒤에야 거름. 이력이 늘수록 비례해서 느려짐. `findAllBySnapshotAtBetweenAndReconciliationStatus`도 범위 조건을 인덱스에서 못 씀 | `CREATE INDEX idx_sales_log_status_settle ON sales_log (reconciliation_status, daily_settlement_id, confirmed_at);`<br>`CREATE INDEX idx_sales_log_status_snapshot ON sales_log (reconciliation_status, snapshot_at);`<br>둘 다 `status`가 선두라 기존 `idx_sales_log_status`는 중복 |
| M7 | `RunReconciliationUseCase` N+1: PG 건(주문번호+상태 조합)마다 `findByOrderNoAndTransactionType`을 1~2회 호출. 쿼리 하나하나는 인덱스를 타지만 DB 왕복이 건수만큼 늘어남 | 주문번호를 `IN`으로 한 번에 읽고 메모리에서 그룹핑 |
| M8 | 리뷰 요약 집계(`summarizeByProductIds`): `(product_id, created_at, id)`에 `rating`이 없어 리뷰마다 테이블 룩업 필요. 상품 목록 한 페이지마다 호출 | `CREATE INDEX idx_reviews_product_rating ON product_reviews (product_id, rating);`로 커버링. 더 줄이려면 `products`에 평균/개수 비정규화 |
| M9 | 무제한 목록 조회: `walletLogRepository.findByWalletId`, `revenueLogRepository.findByWalletId`, `paymentLogRepository.findByBuyerId`는 `ORDER BY`도 페이징도 없이 이력 전체 반환. 인덱스(FK)는 있으니 페이징만 필요 | `Pageable` 도입, `(wallet_id, id)` 정렬 |

## 낮음

- **쓰이지 않는 인덱스와 중복 인덱스** (쓰기 비용만 발생, 삭제 전 `sys.schema_unused_indexes`로 확인)
  - `idx_seller_order`, `idx_seller_state` (`market_order_items`): 읽는 쿼리 없음
  - `idx_login_history_member_id`: 읽는 쿼리 없고 insert만 함
  - `idx_inbox_topic_consumer_group`: 쓰는 쿼리가 안 보임
- **호출 안 되는 메서드**: `OrderRepository.findByBuyerIdOrderByCreatedAtDesc`, `OutboxEventRepository.findPendingEventsWithLock`, `OutboxEventRepository.findByStatusOrderByProcessingStartedAtAsc`, `PaymentLogRepository.findByOrderId`, `PaymentMemberRepository.findByName`
- **`existsByAggregateIdAndEventTypeAndStatus`**: 지금은 호출 없음. 쓰이게 되면 `idx_outbox_aggregate (aggregate_type, aggregate_id)`의 선두 컬럼이 조건에 없어 풀스캔. 쓸 거라면 인덱스를 `(aggregate_id, event_type, status)`로 변경
- **`RefreshToken` 일괄 폐기**: `revokeAllByMemberId`는 `(member_id)`만 탐. `(member_id, revoked_at)`이면 미폐기 토큰만 좁힘
- **쿠폰 목록과 취소 이력**: 테이블이 작고 정렬 대상이 적어 지금은 문제 없음
- **`PESSIMISTIC_WRITE` + `join fetch`**: `MemberCoupon`, `Shipment`, `OrderItem`에서 `FOR UPDATE`가 조인된 두 테이블의 행을 모두 잠글 수 있음. 쿠폰 행 경합이 생기는지는 실제 생성 SQL로 확인 필요
- **전역 설정**: `open-in-view`가 기본값(true)이고 market의 Hikari 풀은 16개(`maximum-pool-size: 16`, 처음 분석에서 10개로 잘못 적음)라 요청 내내 커넥션을 잡고 있음. `GenerationType.IDENTITY`라 JDBC 배치 insert도 안 됨(`saveAll`이 건건이 insert)

## 인덱스 적용 시 주의

- **market, product**: Flyway 마이그레이션 추가(market V4, product V6). market은 local에서 Flyway가 꺼져 있으니 로컬 DB에는 직접 적용
- **payment, settlement**: 마이그레이션 체계 없음. `validate`는 인덱스를 검증하지 않으므로 `@Index`만 추가해서는 prod에 반영되지 않음 → Flyway 도입 또는 수동 DDL
- **엔티티에 `@Table(indexes=…)`도 같이 추가**: 테스트 프로파일의 `create-drop`에도 같은 스키마가 만들어지게 하기 위해서
- **한꺼번에 다 넣지 않음**: 높음(H1~H4)을 먼저 적용하고 효과를 본 뒤 나머지 진행

## 결과

항목별 전/후 수치, 측정 방법, 사용한 명령은 [query-tuning/README.md](query-tuning/README.md) 에 모았습니다.
