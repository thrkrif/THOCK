# H1. 주문 상태(state) 조건 조회 — 메트릭 집계와 스케줄러

## 한 줄 요약

메트릭 수집기가 10초마다 주문 테이블(103만 행)을 11번 풀스캔하던 것을 `GROUP BY` 1회 + `(state, …)` 인덱스 커버링 스캔으로 바꿨다.
수집 1회당 주문 집계 **약 3,580ms → 159ms**, 결제 타임아웃 스케줄러 조회 **402ms → 0.05ms**.

## 1. 증상 — 슬로우 로그에 가장 많이, 가장 오래 찍힌 쿼리

baseline(dev 코드) 5분 부하 중 `mysqldumpslow -s t` 1위:

```
Count: 231  Time=0.51s (118s)  Lock=0.00s (0s)  Rows=1.0 (231)
  select count(o1_0.id) from market_orders o1_0 where o1_0.state='S'
```

한 건을 열어 보면:

```
# Query_time: 0.297880  Rows_sent: 1  Rows_examined: 1028118
# Read_key: 1  Read_next: 0  Read_rnd_next: 1028119
select count(o1_0.id) from market_orders o1_0 where o1_0.state='PENDING_PAYMENT';
```

- 1행을 돌려주려고 102만 행을 읽었다. `Read_rnd_next`(테이블 순차 읽기)가 행 수와 같다 → **풀 테이블 스캔**
- 같은 패턴이 `state` 값만 바꿔 연달아 찍힌다 → `MarketMetricsCollector` 가 `OrderState` 11개마다 `countByState` 를 호출
- 같은 테이블을 결제 타임아웃 스케줄러(60초마다)도 풀스캔: `Query_time: 0.976 Rows_sent: 0 Rows_examined: 1028118`
  (보낼 행이 0건인데 102만 행을 읽음)

## 2. 원인 분석 — EXPLAIN

```sql
SHOW INDEX FROM thock_market_db.market_orders;   -- PRIMARY, order_number, (buyer_id, idempotency_key) 뿐. state 인덱스 없음
EXPLAIN SELECT count(o1_0.id) FROM market_orders o1_0 WHERE o1_0.state = 'CONFIRMED';
```

| type | key | rows | filtered | Extra |
|---|---|---|---|---|
| ALL | NULL | 1,025,869 | 10.00 | Using where |

- `type=ALL`, `key=NULL`: 쓸 인덱스가 없어 전체를 읽는다
- `filtered=10%`: 옵티마이저는 읽은 행의 10%만 조건을 통과할 거라 본다 → 90%는 읽고 버림

```
-> Aggregate: count(o1_0.id)  (actual time=642..642 rows=1 loops=1)
    -> Filter: (o1_0.state = 'CONFIRMED')  (actual time=5.11..615 rows=709990 loops=1)
        -> Table scan on o1_0  (actual time=0.52..521 rows=1.03e+6 loops=1)
```

**원인은 두 가지였다.**
1. 쿼리 구조: 상태 11개를 각각 세서, 10초마다 같은 테이블을 11번 읽음
2. 인덱스 부재: `state` 로 시작하는 인덱스가 없어 매번 테이블 전체(363MB)를 읽음

## 3. 해결

### 3-1. 쿼리 통합 (커밋 `ea08151`)

`OrderRepository`:

```java
// 변경 전: long countByState(OrderState state);  → 상태마다 1번씩 11번 호출
@Query("select o.state as state, count(o) as count from Order o group by o.state")
List<OrderStateCount> countGroupByState();
```

`MarketMetricsCollector`: 결과를 `EnumMap` 에 담고, 결과에 없는 상태(0건)는 0으로 채운다.
Prometheus 게이지 이름과 값은 그대로라 대시보드 변경은 없다.

### 3-2. 인덱스 추가 (Flyway `market V4`)

```sql
CREATE INDEX idx_orders_state_request_payment ON market_orders (state, request_payment_date);
CREATE INDEX idx_orders_state_cancel_date ON market_orders (state, cancel_date);
```

- `GROUP BY state` 는 `state` 가 선두인 인덱스만 처음부터 끝까지 읽으면 된다(테이블보다 훨씬 작음, 이미 state 순 정렬 → 임시 테이블 불필요)
- 타임아웃 스케줄러 `state = ? AND request_payment_date < ?` → 첫 번째 인덱스로 범위 조회
- 정리 스케줄러 `state = ? AND cancel_date < ?` → 두 번째 인덱스로 범위 조회
- 엔티티 `Order` 의 `@Table(indexes=…)` 에도 같은 인덱스를 선언(테스트 프로파일 `create-drop` 스키마와 맞추기 위해)

## 4. 재측정

같은 데이터에서 새 인덱스를 `INVISIBLE`/`VISIBLE` 로 바꿔 가며 쟀다(`scripts/toggle-indexes.sh`, `scripts/measure-all.sh`).
값은 서버 실행 시간(`events_statements_history.TIMER_WAIT`) 5회 중앙값.

| 쿼리 | 전 | 후 | 계획 변화 |
|---|---|---|---|
| 상태 1개 count (기존 방식, 11번 반복) | 325ms × 11 ≈ 3,580ms | — | |
| `GROUP BY state` 1회 (변경 후 방식) | 535ms | **159ms** | `ALL` + `Using temporary` → `index` + `Using index` |
| 결제 타임아웃 조회 (60초마다) | 402ms | **0.05ms** | `ALL` → `ref` |
| 취소 주문 정리, 평상시 (하루치만 대상) | 413ms | **0.07ms** | `ALL` → `range` |
| 취소 주문 정리, 밀린 데이터 11만 건 첫 실행 | 817ms | 551ms | 아래 참고 |

변경 후 `GROUP BY` 계획:

```
-> Group aggregate: count(o1_0.id)  (actual time=57.6..253 rows=9 loops=1)
    -> Covering index scan on o1_0 using idx_orders_state_request_payment  (actual time=0.119..154 rows=1.03e+6 loops=1)
```

여전히 103만 건을 다 읽지만 **테이블이 아니라 인덱스만** 읽고(커버링), 인덱스가 state 순이라 바로 묶는다.

**메트릭 수집 1회당: 3,580ms → 159ms (약 22배).** 10초 주기 기준으로 DB 커넥션을 잡고 있는 시간이 주기의 36% → 1.6%.

## 5. 배운 점: 인덱스가 항상 빠르지는 않다 (정리 스케줄러)

밀린 취소 주문 11만 건(테이블의 10.5%)을 한 번에 지우는 경우를 따로 재 봤다.

| 실행 방식 | 시간 |
|---|---|
| 옵티마이저 선택 (`idx_orders_state_request_payment`, ref) | 506ms |
| `FORCE INDEX (idx_orders_state_cancel_date)` (range) | 507ms |
| `IGNORE INDEX (…)` 로 풀스캔 | **398ms** |

결과가 테이블의 약 10% 이상이면 "인덱스로 찾고 → 행마다 PK 로 다시 읽기(랜덤 I/O)"가 "처음부터 순서대로 읽기(순차 I/O)"보다 느리다.
이 쿼리는 매일 새벽 3시에 돌아서 평상시에는 하루치(수백 건)만 대상이므로(0.07ms) 인덱스를 유지했다.
배포 직후 첫 실행만 약 100ms 손해를 본다.

## 6. 하지 않은 것

- **수집 주기 10초 → 60초**: 인덱스 적용 후 1회 159ms 라 주기를 늘릴 필요가 크지 않았다.
  같은 `collect()` 에서 Outbox 상태와 Kafka lag 도 모으므로 주기를 늘리면 그 지표도 60초마다만 갱신된다. 그래서 바꾸지 않았다.

## 사용한 명령

```sql
SHOW INDEX FROM thock_market_db.market_orders;
EXPLAIN SELECT count(o1_0.id) FROM market_orders o1_0 WHERE o1_0.state = 'CONFIRMED';
EXPLAIN ANALYZE SELECT o1_0.state, count(o1_0.id) FROM market_orders o1_0 GROUP BY o1_0.state;
ALTER TABLE market_orders ALTER INDEX idx_orders_state_request_payment INVISIBLE;   -- 전 측정
ALTER TABLE market_orders ALTER INDEX idx_orders_state_request_payment VISIBLE;     -- 후 측정
SELECT ... FROM market_orders FORCE INDEX (idx_orders_state_cancel_date) WHERE ...;  -- 인덱스 선택 비교
SELECT ... FROM market_orders IGNORE INDEX (idx_orders_state_request_payment, idx_orders_state_cancel_date) WHERE ...;
```

원본 결과: `results/explain/H1-*-before.txt`, `H1-*-after.txt`, `H1-4-index-choice.txt`
