# 쿼리 성능 측정 절차

[query-analysis.md](query-analysis.md)의 항목(H1, H2, …)을 하나 처리할 때마다 따르는 절차입니다. 전체 일정과 더미 데이터는 [query-performance-plan.md](query-performance-plan.md) 참고.

## 도구 세 가지와 쓰임

| 도구 | 답하는 질문 | 언제 |
|---|---|---|
| 슬로우 쿼리 로그 | 임계값보다 오래 걸린 쿼리가 **무엇**인가 | 느린 쿼리를 찾을 때 |
| `performance_schema` digest 통계 | 쿼리 패턴별로 **몇 번, 평균 몇 ms, 몇 행**을 읽었나 | 변경 전/후 수치 비교 |
| `EXPLAIN` / `EXPLAIN ANALYZE` | **왜** 느린가, 인덱스를 탔는가 | 원인 확인, 변경 검증 |

슬로우 로그와 digest는 앱이 실제로 날린 쿼리를 MySQL이 기록하는 것이고, `EXPLAIN`은 내가 쿼리를 직접 넣어 실행 계획을 보는 것입니다.

## 0. 접속

```bash
docker exec -it thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

GUI 클라이언트는 `localhost:3307`로 접속합니다.

## 1. 작업 시작 시 설정

슬로우 로그는 기본이 꺼져 있고 임계값은 10초입니다. `SET GLOBAL`은 MySQL을 재시작하면 사라지므로 작업을 시작할 때마다 확인합니다.

```sql
-- 현재 상태 확인
SHOW GLOBAL VARIABLES
WHERE Variable_name IN ('slow_query_log', 'long_query_time', 'slow_query_log_file',
                        'log_output', 'min_examined_row_limit', 'log_queries_not_using_indexes');

-- 켜기 (0.1초 넘는 쿼리 기록)
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.1;
```

- **설정 후 앱을 재시작**: `long_query_time`의 전역 값은 그 뒤에 새로 맺는 커넥션에만 적용됩니다. HikariCP가 이미 잡고 있는 커넥션은 예전 값(10초)을 그대로 씁니다. 내 SQL 클라이언트 세션도 마찬가지라서 다시 접속하거나 `SET SESSION long_query_time = 0.1;`을 실행합니다.
  ```sql
  SELECT @@GLOBAL.long_query_time, @@SESSION.long_query_time;  -- 둘이 같은지 확인
  ```
- **재시작해도 유지하려면**: `SET GLOBAL` 대신 `SET PERSIST`를 씁니다. 끌 때는 `RESET PERSIST slow_query_log;`
- **`log_queries_not_using_indexes`**: 켜면 시간과 무관하게 인덱스를 안 탄 쿼리가 모두 기록됩니다. 작은 테이블의 쿼리까지 쏟아지므로 기본은 OFF로 두고, 켠다면 `min_examined_row_limit`(예: 1000)으로 걸러냅니다.
- **로그 보기**: `log_output`이 `FILE`이면 컨테이너 안의 파일에 쌓입니다. 파일 이름에 컨테이너 ID가 들어가므로 `slow_query_log_file` 값을 확인해서 씁니다.
  ```bash
  docker exec thock-mysql sh -c 'tail -f /var/lib/mysql/*-slow.log'
  ```
  항목마다 `Query_time`(걸린 시간), `Rows_examined`(읽은 행), `Rows_sent`(반환한 행)가 찍힙니다. `Rows_examined`가 `Rows_sent`보다 훨씬 크면 인덱스 후보입니다.

## 2. 항목마다 반복하는 순서

### 2-1. 대상 SQL 확보

JPA가 만든 실제 SQL이 필요합니다. 슬로우 로그에서 찾거나 digest에서 테이블 이름으로 찾습니다.

```sql
SELECT DIGEST_TEXT, COUNT_STAR
FROM performance_schema.events_statements_summary_by_digest
WHERE DIGEST_TEXT LIKE '%market_orders%'
ORDER BY COUNT_STAR DESC LIMIT 10;
```

### 2-2. 변경 전 측정

```sql
SHOW INDEX FROM thock_market_db.market_orders;   -- 지금 있는 인덱스

EXPLAIN SELECT state, COUNT(*) FROM thock_market_db.market_orders GROUP BY state;
EXPLAIN ANALYZE SELECT state, COUNT(*) FROM thock_market_db.market_orders GROUP BY state;
```

- `EXPLAIN`은 계획만 보여 주고 실행하지 않습니다.
- `EXPLAIN ANALYZE`는 **실제로 실행**하고 단계별 실제 시간과 행 수를 보여 줍니다. 느린 쿼리는 그만큼 기다려야 합니다.
- 같은 쿼리를 2~3번 실행하고 뒤쪽 값을 기록합니다. 첫 실행은 데이터가 메모리(버퍼 풀)에 없어서 더 느리게 나옵니다.

### 2-3. 부하를 주고 digest 기록

```sql
TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;  -- 통계만 비움
```

그다음 k6 시나리오를 돌리거나, 스케줄러 쿼리라면 주기가 몇 번 돌 때까지 기다린 뒤 조회합니다.

```sql
SELECT DIGEST_TEXT,
       COUNT_STAR                    AS exec_count,
       ROUND(AVG_TIMER_WAIT/1e9, 2)  AS avg_ms,
       ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_sec,
       SUM_ROWS_EXAMINED             AS rows_examined,
       SUM_ROWS_SENT                 AS rows_sent,
       SUM_NO_INDEX_USED             AS no_index_used
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'thock_market_db'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 20;
```

앱 커넥션은 스키마가 정해져 있어 `SCHEMA_NAME`으로 걸러집니다. 내가 클라이언트에서 직접 친 쿼리는 `SCHEMA_NAME`이 다를 수 있습니다.

### 2-4. 변경 적용

인덱스 DDL이나 쿼리 수정을 **한 번에 하나만** 적용합니다. 여러 개를 같이 넣으면 어느 것의 효과인지 알 수 없습니다.

### 2-5. 변경 후 측정

2-2와 2-3을 그대로 반복합니다. 통계 초기화 → 같은 부하 → 같은 조회 순서를 지킵니다.

### 2-6. 기록하고 마무리

- 아래 표를 채워 전/후를 남깁니다.
- 효과가 확인되면 Flyway 마이그레이션과 엔티티 `@Table(indexes=…)`에 반영합니다. 효과가 없으면 인덱스를 지웁니다(`DROP INDEX … ON …`).
- [query-analysis.md](query-analysis.md)의 체크박스를 체크합니다.

| 항목 | 전 | 후 |
|---|---|---|
| `EXPLAIN` `type` / `key` / `Extra` | | |
| `EXPLAIN ANALYZE` 실제 시간(ms) | | |
| digest `avg_ms` | | |
| digest `rows_examined` ÷ `exec_count` | | |
| k6 p95 / 에러율 (해당 시) | | |

## 3. EXPLAIN 읽는 법

| 컬럼 | 보는 것 |
|---|---|
| `type` | 접근 방식. 아래로 갈수록 나쁨: `const` / `eq_ref`(PK·유니크 1건) → `ref`(인덱스 동등 조건) → `range`(인덱스 범위) → `index`(인덱스 전체 스캔) → `ALL`(테이블 전체 스캔) |
| `possible_keys` / `key` | 쓸 수 있는 인덱스 / 실제로 고른 인덱스. `key`가 `NULL`이면 인덱스를 안 탐 |
| `rows` | 읽을 것으로 **예상**한 행 수 |
| `filtered` | 읽은 행 중 조건을 통과할 것으로 예상한 비율(%). 낮으면 읽고 버리는 행이 많음 |
| `Extra` | `Using index`: 인덱스만으로 해결(커버링, 좋음)<br>`Using index condition`: 인덱스 단계에서 조건을 먼저 거름<br>`Using where`: 행을 읽은 뒤에 거름<br>`Using temporary`: 임시 테이블 사용(`GROUP BY` 등)<br>`Using filesort`: 인덱스 순서를 못 써서 따로 정렬 |

`Using temporary`와 `Using filesort`가 큰 테이블에서 보이면 개선 대상입니다.

`EXPLAIN ANALYZE`는 트리 형태로 나오고 **안쪽(아래) 줄부터** 실행됩니다.

```
-> Table scan on market_orders  (cost=114049 rows=996619) (actual time=4.07..999 rows=1.03e+6 loops=1)
```

- `cost`, 앞쪽 `rows`: 옵티마이저의 예상값
- `actual time=A..B`: 첫 행까지 A ms, 마지막 행까지 B ms. 하위 단계 시간이 포함된 값
- 뒤쪽 `rows`, `loops`: 실제로 반환한 행 수와 반복 횟수. 조인 안쪽은 `rows × loops`가 실제 처리량
- 예상 `rows`와 실제 `rows`가 크게 다르면 통계가 낡은 것입니다. `ANALYZE TABLE 테이블명;`으로 갱신합니다.

## 4. 예시: H1 변경 전 (2026-11-17, `market_orders` 약 100만 행)

`GROUP BY state` 집계 쿼리, `state` 인덱스가 없는 상태입니다.

```
EXPLAIN:          type=ALL, key=NULL, rows=996619, Extra=Using temporary

EXPLAIN ANALYZE:
-> Table scan on <temporary>  (actual time=1369..1369 rows=9 loops=1)
    -> Aggregate using temporary table  (actual time=1369..1369 rows=9 loops=1)
        -> Table scan on market_orders  (cost=114049 rows=996619) (actual time=4.07..999 rows=1.03e+6 loops=1)
```

테이블 전체(103만 행)를 읽는 데 약 1초, 임시 테이블로 집계를 마치기까지 약 1.37초가 걸립니다. 인덱스를 넣은 뒤 같은 쿼리로 `type`, `key`, `Extra`, 실제 시간이 어떻게 바뀌는지 비교합니다.

## 5. 주의

- **측정이 끝나면 끄기**: `SET GLOBAL slow_query_log = OFF;` 로그 파일이 계속 커집니다.
- **데이터가 적으면 의미 없음**: 행이 적은 테이블은 인덱스가 있어도 옵티마이저가 전체 스캔을 고릅니다. 더미 데이터가 적재된 상태에서 측정합니다.
- **`EXPLAIN ANALYZE`는 SELECT에만**: 실제로 실행하므로 `DELETE`/`UPDATE`에 쓰면 데이터가 바뀝니다. 그런 쿼리는 `EXPLAIN`만 쓰거나 같은 조건의 `SELECT`로 바꿔서 봅니다.
- **digest 시간은 MySQL 안에서 쓴 시간만**: 네트워크와 앱 처리 시간은 빠져 있으므로 k6 응답 시간과 함께 봅니다.
