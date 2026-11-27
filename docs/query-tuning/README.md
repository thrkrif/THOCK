# 쿼리 성능 튜닝 기록 (task #22)

[정적 분석](../query-analysis.md)에서 뽑은 후보(H1~H4, M1~M9)를 **실제로 서버를 띄워 슬로우 로그로 확인하고, EXPLAIN 으로 원인을 찾고, 고친 뒤 같은 조건으로 다시 재서** 비교한 기록이다.
작업일 2026-11-27.

## 결과 요약

### API 응답 시간 (k6, 같은 시나리오 · 같은 데이터)

| API | 전 p95 | 후 p95 | 변화 | 관련 |
|---|---|---|---|---|
| 상품 목록의 리뷰 요약 `GET /products/reviews/summaries` | 1,153ms | 80ms | 14.5배 | M8 |
| 내 상품 목록 `GET /products/me` (상품 1,495개) | 323ms | 12ms | 27.8배 | H4 |
| 내 상품 목록 `GET /products/me` (상품 2.9만 개) | 327ms | 16ms | 19.8배 | H4 |
| 상품 목록 `sort=price` | 1,152ms | 169ms | 6.8배 | M1 |
| 판매자 배송 목록 `GET /shipments/me` | 955ms | 216ms | 4.4배 | M4 |
| 상품 검색, 결과 없음 | 822ms | 266ms | 3.1배 | H3 |
| 상품 검색, 흔한 키워드 | 531ms | 236ms | 2.3배 | H3 |
| 상품 검색, 드문 키워드 | 821ms | 484ms | 1.7배 | H3 |
| 상품 목록 `size=2000` | 225ms | 136ms | 1.6배 | M1 |
| 상품 목록 (기본) | 431ms | 288ms | 1.5배 | (간접) |
| 내 주문 목록 `GET /orders` | 210ms | 42ms | 5.1배 | (간접) |

- 전 = `dev` 브랜치 코드(`3b5f5fc`) + 기존 인덱스, 후 = 이 브랜치 코드 + 새 인덱스. 두 번 모두 실패율 0%
- `(간접)` 표시한 두 API(내 주문 목록, 기본 상품 목록)는 이번에 해당 쿼리를 바꾸지 않았다(M2 미적용, 기본 목록은 원래 `id DESC`).
  빨라진 것은 같은 DB 를 쓰는 배경 쿼리(10초마다 도는 메트릭 풀스캔 등)와 다른 API 의 풀스캔이 줄어든 간접 효과다

### 배치/스케줄러

| 작업 | 전 | 후 | 관련 |
|---|---|---|---|
| 대사 배치 1일치 (PG 2,723건) | 11.6초 | **0.65초** | M7 |
| 메트릭 수집 1회의 주문 집계 (10초마다) | 약 3,580ms | 159ms | H1 |
| Outbox 상태 집계 (10초마다) | 약 290ms | 69ms | M5 |
| 결제 타임아웃 조회 (60초마다) | 402ms | 0.05ms | H1 |
| 정산 후보 조회 | 1,170ms | 33ms | M6 |

### 쿼리 단위 (서버 실행 시간 중앙값)

| # | 쿼리 | 전 | 후 | 바꾼 것 |
|---|---|---|---|---|
| H1 | 상태별 주문 집계 | 325ms × 11번 | 159ms × 1번 | `GROUP BY` 1회 + `(state, …)` 인덱스 |
| H2 | 결제 `findByOrderId` | 296ms | 0.08ms | `order_id` 인덱스 |
| H3 | 상품 검색 (count + 목록) | 211~544ms | 105~223ms | `(name)` 인덱스 + 결과 건수별 조회 방식 |
| H4 | 판매자 상품 count | 138ms | 0.2ms | `(seller_id, id)` 인덱스 |
| M1 | 가격 정렬 목록 | 242ms (filesort) | 정렬 차단 | 정렬 화이트리스트, 페이지 상한 100 |
| M3 | 자동 구매 확정 대상 | 379ms | 163ms | `(state, delivered_at)` 인덱스 |
| M4 | 판매자 배송 목록 | 298ms + 추가 SELECT 56번 | 72ms + 추가 SELECT 0번 | `(seller_id, created_at)` 인덱스 + fetch join |
| M6 | 대사 잔여건 | 66ms | 0.05ms | `(status, snapshot_at)` 인덱스 |
| M8 | 리뷰 요약 집계 | 217ms | 4.3ms | `(product_id, rating)` 커버링 인덱스 |

### 슬로우 로그 (100ms 이상)

| | 전 | 후 |
|---|---|---|
| 5분 동안 처리한 k6 요청 | 2,306 | 16,640 (7.2배) |
| 실행된 SQL 문장 | 25,508 | 118,365 |
| 100ms 이상 문장 비율 | 6.1% (1,568건) | 1.5% (1,793건) |
| 1초 이상 문장 | 38건 | 1건 |
| 슬로우 로그 총 시간 1위 | 주문 상태별 count 풀스캔 (231번 × 0.51s) | 카테고리 목록 count (893번 × 0.15s, [M1 남은 비용](M1-product-paging.md)) |

같은 VU 수로 돌려서 빨라진 쪽이 요청을 7배 더 보냈다. 그래서 슬로우 로그 "건수"는 비교에 쓰지 않고 비율로 봤다.

## 진행 과정

### 0. 데이터 준비

로컬 Docker MySQL(`thock-mysql`)에 더미 데이터를 넣었다(`docs/perf-seed/run.sh`).

| 테이블 | 행 수 | 비고 |
|---|---|---|
| market_orders / market_order_items | 103만 / 123만 | 구매자 일부에 몰림(최다 9,213건) |
| products | 50만 | 판매자 300명, 일부에 몰림(최다 2.9만 개) |
| sales_log | 100만 | 1년치 |
| payment_payments | 103만 | 주문 1건당 1건 |
| market_shipments | 94만 | 판매자 최다 5.4만 건 |
| outbox_event (market) | 52만 | SENT 8일치 + 재시도 한도 초과 FAILED |
| product_reviews | 100만 | 최신 상품에 몰림(최다 11,684건) |

적재 후 `ANALYZE TABLE` 로 통계를 갱신했다. 행이 적으면 옵티마이저가 인덱스가 있어도 풀스캔을 고르므로, 실제처럼 쌓인 상태에서 재야 의미가 있다.

### 1. 기존 코드로 서버를 띄우고 슬로우 로그 켜기

```sql
SET GLOBAL slow_query_log_file = '/var/lib/mysql/slow-baseline.log';
SET GLOBAL long_query_time = 0.1;
SET GLOBAL log_slow_extra = ON;
SET GLOBAL slow_query_log = ON;
TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
TRUNCATE TABLE performance_schema.events_statements_histogram_global;
```

그다음 `dev` 코드를 빌드해 product/market/payment/settlement 를 local 프로파일로 띄웠다(`scripts/services.sh`).
`long_query_time` 은 새 커넥션부터 적용되므로 **설정을 먼저 하고 서버를 띄웠다.**

#### 왜 100ms 인가

MySQL 기본값은 10초라 OLTP API 의 느린 쿼리는 하나도 안 잡힌다. 기준은 다음 세 가지로 정했다.

1. **이 프로젝트의 응답 목표**: k6 시나리오의 기준이 `p(95) < 1500ms`(`order-flow.js`, `member-flow.js`, `order-kafka-e2e.js`)다.
   API 한 번에 쿼리가 여러 개 나가고(목록 + count + 리뷰 요약, 주문 생성의 서비스 간 호출) 서비스 간 Feign 호출도 있어서,
   쿼리 하나가 100ms 를 넘으면 예산의 7% 이상을 혼자 쓴다
2. **커넥션 풀**: product/payment/settlement 는 HikariCP 기본 10개, market 은 16개다. 쿼리 하나가 커넥션을 100ms 잡으면
   그 쿼리만으로 초당 100~160건이 상한이다. 동시 요청이 몰리면 커넥션 대기가 먼저 터진다(#21 지연 커넥션 작업과 같은 맥락)
3. **실측 분포**: baseline 부하 중 전체 문장의 실행 시간 분포(`events_statements_histogram_global`)

| 실행 시간 | 문장 수 | 비율 |
|---|---|---|
| 1ms 이하 | 19,735 | 77.4% |
| 1~10ms | 3,553 | 13.9% |
| 10~100ms | 652 | 2.6% |
| 100ms~1s | 1,530 | 6.0% |
| 1s 초과 | 38 | 0.1% |

정상 쿼리 대부분이 10ms 안에 끝나므로, 100ms 는 정상 범위보다 10배 위에 있어 "이상한 것만" 걸린다.

운영 서버가 로컬보다 몇 배 느린지는 운영 환경(EC2)이 없어 재지 못했다. 그래서 "운영에서는 N배" 같은 환산은 하지 않았다.

### 2. 부하를 주고 슬로우 로그 읽기

`k6/query-tuning.js` 로 프론트가 실제로 보내는 요청(목록 → 리뷰 요약, 검색, 내 상품, 내 주문, 판매자 배송)과
문제가 될 만한 요청(가격 정렬, `size=2000`)을 5분 동안 보냈다. 스케줄러(메트릭 10초, 타임아웃 60초, Outbox 폴러)는 그동안 그대로 돌았다.
대사 배치는 수동 API 로 실행했다(`scripts/reconcile.sh`).

```bash
mysqldumpslow -s t -t 30 slow-baseline.log      # 총 시간 순 상위 패턴
```

상위 패턴과 원본 기록은 `results/baseline/slow-baseline.summary.txt`, `slow-excerpts.txt` 에 있다. 읽는 법은 [commands.md](commands.md).
슬로우 로그에 안 잡히는 N+1(M7)은 digest 통계의 실행 횟수(`COUNT_STAR`)로 찾았다.

### 3. EXPLAIN 으로 원인 찾기

느린 쿼리마다 `EXPLAIN`/`EXPLAIN ANALYZE` 를 봤다(`scripts/explain.sh`). 주로 본 신호:

- `type=ALL`, `key=NULL`: 인덱스가 없어 전체를 읽음 (H1, H2, H3 count, H4, M3, M4)
- `filtered` 가 낮거나 `Rows_examined` ≫ `Rows_sent`: 많이 읽고 거의 다 버림 (H1 10%, M6 97만 건 읽고 5,455건)
- `Using temporary`, `Using filesort`: 임시 테이블, 따로 정렬 (H1 GROUP BY, M1 가격 정렬, M4)
- `Using index` 가 없음: 인덱스로 찾은 뒤 행을 다시 읽음 (M8)
- 실행 횟수가 비정상적으로 많음: N+1 (M7, M4 의 지연 로딩)

### 4. 고치기

| 종류 | 항목 |
|---|---|
| 인덱스 추가 (Flyway: market V4~V6, product V6~V8) | H1, H3, H4, M3, M4, M8 |
| 인덱스 추가 (엔티티 `@Index`, ddl-auto=update / 운영 수동 DDL) | H2, M6 |
| 쿼리/비즈니스 로직 변경 | H1·M5 상태별 count 통합, H3 검색 실행 방식, M1 정렬/페이지 제한, M4 fetch join, M7 N+1 제거 |
| 측정 후 적용하지 않음 | H3 FULLTEXT, M2 인덱스, M5 정리 DELETE 변경 |

인덱스는 한 번에 넣지 않고 쿼리마다 전/후를 쟀다. 같은 데이터로 비교하려고 새 인덱스를 지우지 않고
`ALTER TABLE … ALTER INDEX … INVISIBLE/VISIBLE` 로 옵티마이저에서만 숨겼다 켰다(`scripts/toggle-indexes.sh`).

### 5. 다시 재기

- 쿼리 단위: 서버 실행 시간 5회 중앙값(`scripts/measure-all.sh`, `results/explain/summary-before.txt`, `summary-after.txt`)
- API 단위: 이 브랜치 코드로 빌드해 같은 k6 시나리오, 같은 대사 날짜로 다시 실행(`results/after/`)

### 측정하면서 바로잡은 것

- **EXPLAIN ANALYZE 의 시간은 실행 단계만 잰다.** FULLTEXT count 가 0ms 로 보였지만 실제 1.26초였다 → 서버 시간(`events_statements_history.TIMER_WAIT`)으로 통일
- **DELETE 계획은 같은 조건의 SELECT 와 다를 수 있다.** SELECT 로는 13배 빨라 보였지만 DELETE 는 전후 모두 풀스캔이었다(M5) → `EXPLAIN DELETE` + 롤백 트랜잭션으로 재측정
- **결과가 테이블의 10% 이상이면 인덱스가 풀스캔보다 느리다.** 밀린 취소 주문 정리(H1)
- **첫 after 측정은 버렸다.** 같은 시간에 M5 DELETE 실험(8만 건, 롤백)을 돌려 다른 쿼리가 undo 를 거슬러 읽느라 느려졌다. 실험을 멈추고 다시 쟀다

## 문서

| 문서 | 내용 |
|---|---|
| [commands.md](commands.md) | 쓴 명령어 전부와 출력 읽는 법 |
| [H1-order-state.md](H1-order-state.md) | 주문 상태 집계/스케줄러 |
| [H2-payment-order-id.md](H2-payment-order-id.md) | 결제 조회, 중복 결제 데이터 발견 |
| [H3-product-search.md](H3-product-search.md) | 상품 검색, FULLTEXT 를 버린 이유 |
| [H4-seller-products.md](H4-seller-products.md) | 판매자 상품 목록 |
| [M1-product-paging.md](M1-product-paging.md) | 페이지 크기/정렬 제한 |
| [M2-my-orders.md](M2-my-orders.md) | 내 주문 목록 (인덱스 효과 없음) |
| [M3-M4-orders-batch-and-shipments.md](M3-M4-orders-batch-and-shipments.md) | 자동 구매 확정, 판매자 배송 목록 |
| [M5-outbox.md](M5-outbox.md) | Outbox 집계/정리 |
| [M6-M7-settlement.md](M6-M7-settlement.md) | 정산 인덱스, 대사 N+1 |
| [M8-review-summary.md](M8-review-summary.md) | 리뷰 요약 커버링 인덱스 |
| [deferred.md](deferred.md) | 하지 않은 것과 이유, 데이터 한계 |
| `sql/manual-ddl-payment-settlement.sql` | Flyway 없는 서비스의 운영 반영 DDL |
| `scripts/` | 측정 스크립트 (사용법은 commands.md 6장) |
| `results/` | 원본 측정 결과 (baseline, after, explain) |

## 측정 환경

- Apple M1, 16GB / Docker Desktop 6 CPU, 8GB
- MySQL 8.0.45, `innodb_buffer_pool_size` 128MB(기본값). 주요 테이블(270~440MB)이 버퍼 풀보다 커서 풀스캔은 매번 디스크를 읽는다
- Java 21 로 실행(빌드 타깃 17), Spring Boot 3.4.1, k6 1.6.1
- 애플리케이션 4개, MySQL, Redpanda, k6 를 한 노트북에서 같이 실행. 절대값보다 같은 조건의 전/후 비율을 봐야 한다
