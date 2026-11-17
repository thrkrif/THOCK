# 쿼리 성능 점검 작업 계획

정적 분석 결과([query-analysis.md](query-analysis.md))의 인덱스 후보를 더미 데이터 + EXPLAIN / digest 통계 / k6 로 검증하는 계획.

## 역할 분담

| 단계 | 담당 |
|---|---|
| MySQL 기동, 스키마 확인, 더미 데이터 적재 | Claude |
| 통계 초기화, 슬로우 로그 설정, baseline 측정, 인덱스 수정, 재측정, 비교 | 직접 |

## 환경

- MySQL 8.0, 컨테이너 `thock-mysql` (호스트 포트 3307), 볼륨 `beadv4_4_refactoring_be_mysql-data`
- DB: `thock_market_db`, `thock_product_db`, `thock_settlement_db`, `thock_payment_db`
- 기존 데이터는 k6로 만든 것이라 삭제해도 무방

## 1. 더미 데이터 적재 (Claude)

| 테이블 | 행 수(목표) | 비고 |
|---|---|---|
| `market_orders` (+ `market_order_items`) | 약 100만 | FK 부모(주문) 먼저. state 분포는 완료 상태 다수, `PENDING_PAYMENT`/`CANCELLED`는 소수 |
| `products` | 약 50만 | 이름 검색 / seller_id 조회용. seller_id는 일부 판매자에 몰리게 |
| `sales_log` | 약 100만 | reconciliation_status: MATCH 다수, PENDING 소수. `daily_settlement_id`는 대부분 채움 |

예상 용량: 약 2~3GB (인덱스 생성/binlog 포함 시 최대 5GB 안팎).

## 2. 측정 준비 (직접)

항목별 상세 절차와 `EXPLAIN` 읽는 법은 [query-measurement-guide.md](query-measurement-guide.md) 참고.

1. 슬로우 로그 설정 (선택): `SET GLOBAL slow_query_log=ON; SET GLOBAL long_query_time=0.1;`
2. digest 통계 초기화:
   ```sql
   TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
   ```
   - 통계용 시스템 테이블만 비움 (데이터 테이블 아님)
   - baseline 직전, 인덱스 변경 후 재측정 직전에 각각 실행

## 3. baseline 측정 (직접)

인덱스를 바꾸기 전 상태에서 k6 시나리오 실행 후 digest 조회:

```sql
SELECT DIGEST_TEXT,
       COUNT_STAR                    AS exec_count,
       ROUND(AVG_TIMER_WAIT/1e9, 2)  AS avg_ms,
       ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_sec,
       SUM_ROWS_EXAMINED             AS rows_examined,
       SUM_ROWS_SENT                 AS rows_sent
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'thock_market_db'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 20;
```

- digest 시간은 MySQL 실행 구간만 포함. k6 응답시간(전체 구간)도 같이 기록
- 스케줄러 쿼리(타임아웃, 정산 배치 등)는 k6가 안 건드리므로 `EXPLAIN ANALYZE`로 직접 확인

## 4. 인덱스 변경 및 재측정 (직접)

인덱스는 **하나씩** 추가하고, 추가마다 통계 초기화 → 같은 k6 → 조회. 우선순위:

| # | 대상 | DDL |
|---|---|---|
| H1 | `market_orders` state | `CREATE INDEX idx_orders_state_request_payment ON market_orders (state, request_payment_date);` (+ `(state, cancel_date)`) |
| H2 | `payment_payments.order_id` | `ALTER TABLE payment_payments ADD UNIQUE INDEX uk_payment_order_id (order_id);` |
| H3 | `products.name` 검색 | `ALTER TABLE products ADD FULLTEXT INDEX ft_products_name (name) WITH PARSER ngram;` (쿼리 변경 필요) |
| H4 | `products.seller_id` | `CREATE INDEX idx_products_seller_id ON products (seller_id, id);` |

이후 중간 우선순위(M1~M9)는 분석 보고서 참고.

## 5. 비교 항목

쿼리별 전/후: `rows_examined`, `avg_ms`, `total_sec`, k6 p95 응답시간 / 에러율.

## 6. 적용 시 주의

- market/product: Flyway 마이그레이션 추가 (local 프로파일은 Flyway 꺼져 있음)
- payment/settlement: 마이그레이션 체계 없음 (prod `validate`는 인덱스를 검증하지 않음) → Flyway 도입 또는 수동 DDL
- 엔티티 `@Table(indexes=...)`에도 반영 (테스트 `create-drop` 스키마 일치)
- 인덱스는 쓰기 비용이 있으므로 효과가 확인된 것만 반영
