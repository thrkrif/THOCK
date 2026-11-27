# M5. Outbox 쿼리 — 상태별 count 통합 (정리 DELETE 변경은 효과가 없어 되돌림)

## 한 줄 요약

- 메트릭 수집기가 10초마다 Outbox 상태 4개를 각각 세던 것을 `GROUP BY` 1회로 합쳤다: 수집 1회당 **약 290ms → 69ms**
- 정리 배치 DELETE 에 조건을 더해 인덱스를 타게 하려 했지만, **실제 DELETE 계획과 시간이 전/후 같아서 되돌렸다**
- 재시도 한도를 넘긴 FAILED 조회는 1,906건 기준 5ms 라 손대지 않았다

## 1. 상태별 count (10초마다 4번)

### 증상

```
# Query_time: 0.145971  Rows_sent: 1  Rows_examined: 514483  Read_next: 514483
select count(oe1_0.id) from outbox_event oe1_0 where oe1_0.status='SENT';
```

`idx_outbox_status_created (status, created_at)` 로 범위를 찾기는 하지만(`Read_next`) SENT 가 51만 건이라 그만큼 읽는다.
이걸 PENDING, PROCESSING, SENT, FAILED 4번 반복한다(H1 과 같은 구조).

### 해결

```java
// OutboxEventRepository (common)
@Query("select o.status as status, count(o) as count from OutboxEvent o group by o.status")
List<OutboxStatusCount> countGroupByStatus();
```

`MarketMetricsCollector` 는 결과를 `EnumMap` 에 담고 없는 상태는 0으로 채운다.

### 결과

| | 전 | 후 |
|---|---|---|
| SENT 1개 count | 72ms (× 4번 ≈ 290ms) | — |
| `GROUP BY status` 1회 | — | **69ms** (`index`, `Using index`: 인덱스만 한 번 훑음) |

## 2. 정리 배치 `deleteSentEventsBefore` — 시도했다가 되돌림

### 가설

```sql
DELETE FROM outbox_event WHERE status = 'SENT' AND sent_at < ?
```

인덱스 `(status, created_at)` 에 `sent_at` 이 없으니 SENT 전체를 읽고 거를 것이다.
발행은 항상 생성 이후라(`created_at <= sent_at`) `AND created_at < ?` 를 더해도 삭제 대상은 같고, 인덱스 범위를 쓸 수 있을 것이다.

### SELECT 로 확인했을 때 (같은 조건의 count)

| | 시간 | 계획 |
|---|---|---|
| `sent_at` 만 | 2,261ms | `ref` (status) → 51만 건마다 행을 다시 읽음(랜덤 I/O) |
| `created_at` 추가 | 169ms | 풀스캔(순차 I/O) |

13배 빨라 보였다.

### 그런데 실제 DELETE 는 달랐다

```sql
EXPLAIN DELETE FROM outbox_event WHERE status='SENT' AND sent_at < now() - INTERVAL 7 DAY;                              -- type=ALL
EXPLAIN DELETE FROM outbox_event WHERE status='SENT' AND created_at < now() - INTERVAL 7 DAY AND sent_at < now() - INTERVAL 7 DAY;  -- type=ALL
```

DELETE 는 처음부터 풀스캔을 고르고 있었다. 실제 시간도 롤백 트랜잭션 안에서 재 보니 차이가 없었다.

```sql
START TRANSACTION; DELETE FROM outbox_event WHERE ...; ROLLBACK;   -- 3번씩, events_statements_history 로 시간 확인
```

| | 8만 건 삭제 시간 (3회) |
|---|---|
| 기존 조건 | 3.8s, 7.0s, 9.7s |
| `created_at` 추가 | 5.3s, 5.6s, 7.7s |

8만 행을 지우는 비용(undo 기록, 인덱스 2개 갱신)이 대부분이라 찾는 방식은 거의 영향이 없다.
**효과가 확인되지 않은 변경이라 되돌렸다**(커밋 `refactor: Outbox 정리 DELETE 조건 변경 되돌림 (M5)`).

교훈: DELETE/UPDATE 의 계획은 같은 조건의 SELECT 와 다를 수 있다. `EXPLAIN DELETE` 로 직접 확인하고, 시간은 트랜잭션 + ROLLBACK 으로 잰다.

## 3. 재시도 대상 FAILED 조회 — 그대로 둠

```sql
SELECT ... FROM outbox_event WHERE status = 'FAILED' AND retry_count < 5 ORDER BY created_at;   -- 10초마다
```

`ref` 로 FAILED 1,906건을 읽고 거른다: 4.6ms. (적재 때 재시도 가능했던 97건은 baseline 실행 중 재발행되어 SENT 가 됐고, 남은 1,906건은 모두 재시도 한도 초과라 결과는 0건)
한도 초과 건이 계속 쌓이는 구조이긴 하지만 지금 비용은 작다. `DEAD` 상태 분리는 Outbox 테이블 컬럼이 MySQL ENUM 이라
모든 서비스 테이블 변경이 필요해 이번에는 하지 않았다([deferred.md](deferred.md)).

## 사용한 명령

```sql
EXPLAIN SELECT oe1_0.status, count(oe1_0.id) FROM outbox_event oe1_0 GROUP BY oe1_0.status;
EXPLAIN DELETE FROM outbox_event WHERE status='SENT' AND sent_at < now() - INTERVAL 7 DAY;
START TRANSACTION; DELETE FROM outbox_event WHERE status='SENT' AND sent_at < now() - INTERVAL 7 DAY; ROLLBACK;
SELECT ROUND(TIMER_WAIT/1e9, 1), ROWS_AFFECTED FROM performance_schema.events_statements_history
WHERE THREAD_ID = PS_CURRENT_THREAD_ID() AND SQL_TEXT LIKE 'DELETE%' ORDER BY EVENT_ID DESC LIMIT 3;
```
