# 쿼리 튜닝에 쓴 명령어 정리

이번 작업에서 실제로 실행한 MySQL 명령어를 용도별로 모았다. 모두 MySQL 8.0.45(로컬 Docker `thock-mysql`)에서 실행했다.
접속:

```bash
docker exec -it thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

## 1. 슬로우 쿼리 로그

임계값보다 오래 걸린 문장을 파일에 남긴다. "무엇이 느린가"를 찾는 첫 단계.

```sql
-- 현재 설정 확인
SHOW GLOBAL VARIABLES
WHERE Variable_name IN ('slow_query_log', 'long_query_time', 'slow_query_log_file',
                        'log_output', 'log_slow_extra', 'min_examined_row_limit',
                        'log_queries_not_using_indexes');

-- 측정 단위마다 새 파일로 기록 (전/후 로그를 섞지 않기 위해)
SET GLOBAL slow_query_log = OFF;
SET GLOBAL slow_query_log_file = '/var/lib/mysql/slow-baseline.log';
SET GLOBAL long_query_time = 0.1;        -- 0.1초(100ms) 이상 기록. 기본값은 10초
SET GLOBAL log_slow_extra = ON;          -- Read_rnd_next, Sort_rows 같은 상세 지표를 같이 기록 (8.0.14+)
SET GLOBAL slow_query_log = ON;

-- 끄기
SET GLOBAL slow_query_log = OFF;
SET GLOBAL long_query_time = 10;
```

| 변수 | 의미 | 이번 값 |
|---|---|---|
| `slow_query_log` | 슬로우 로그 사용 여부 | ON (측정 중에만) |
| `long_query_time` | 이 시간(초) 이상 걸린 문장을 기록 | 0.1 |
| `log_output` | 기록 위치. `FILE` 또는 `TABLE`(mysql.slow_log) | FILE (기본값) |
| `log_slow_extra` | 읽은 행 종류별 횟수, 정렬 정보 등 추가 필드 기록 | ON |
| `log_queries_not_using_indexes` | 시간과 무관하게 인덱스를 안 쓴 문장도 기록 | OFF (작은 테이블 쿼리까지 쏟아짐) |
| `min_examined_row_limit` | 읽은 행이 이보다 적으면 기록 안 함 | 0 |

**함정: `long_query_time` 의 GLOBAL 값은 새로 맺는 커넥션에만 적용된다.**
HikariCP 가 이미 잡고 있는 커넥션은 예전 값(10초)을 그대로 쓴다. 그래서 설정을 바꾼 **뒤에** 애플리케이션을 시작했다.

```sql
SELECT @@GLOBAL.long_query_time, @@SESSION.long_query_time;  -- 내 세션 값도 따로 확인
SET SESSION long_query_time = 0.1;                            -- 내 세션만 바꿀 때
```

### 로그 한 건 읽는 법

```
# Query_time: 0.297880  Lock_time: 0.000015 Rows_sent: 1  Rows_examined: 1028118 ...
#   Read_first: 1 Read_key: 1 Read_next: 0 Read_rnd_next: 1028119 Sort_rows: 0 Created_tmp_tables: 0 ...
select count(o1_0.id) from market_orders o1_0 where o1_0.state='PENDING_PAYMENT';
```

- `Query_time`: 서버가 이 문장에 쓴 시간(초)
- `Rows_examined` vs `Rows_sent`: 1을 돌려주려고 102만 행을 읽었다 → 인덱스가 없다는 신호
- `Read_rnd_next`: 테이블을 처음부터 순서대로 읽은 횟수. 행 수와 같으면 **풀 테이블 스캔**
- `Read_key`/`Read_next`: 인덱스로 찾아 들어간/이어 읽은 횟수
- `Sort_rows`, `Created_tmp_tables`: 정렬, 임시 테이블 사용

### 요약: mysqldumpslow

로그를 쿼리 패턴별로 묶어(숫자는 N, 문자열은 'S') 총 시간 순으로 보여 준다. 호스트의 MySQL 클라이언트에 들어 있다.

```bash
docker cp thock-mysql:/var/lib/mysql/slow-baseline.log .
mysqldumpslow -s t -t 30 slow-baseline.log     # -s t: 총 시간 순, -t 30: 상위 30개
```

```
Count: 231  Time=0.51s (118s)  Lock=0.00s (0s)  Rows=1.0 (231)
  select count(o1_0.id) from market_orders o1_0 where o1_0.state='S'
```

`Count` 실행 횟수, `Time=평균 (합계)`, `Rows=평균 반환 행`.

## 2. performance_schema 통계

슬로우 로그는 임계값을 넘은 문장만 남지만, `performance_schema` 는 **모든** 문장을 패턴(digest)별로 집계한다.
전/후 비교의 기준 데이터로 썼다.

```sql
-- 측정 시작 전 통계 초기화 (통계 테이블만 비움, 데이터와 무관)
TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
TRUNCATE TABLE performance_schema.events_statements_histogram_global;

-- 쿼리 패턴별: 실행 횟수, 평균/최대 시간, 실행당 읽은 행/보낸 행, 인덱스 미사용 횟수
SELECT SCHEMA_NAME AS db,
       COUNT_STAR AS exec_count,
       ROUND(AVG_TIMER_WAIT/1e9, 2) AS avg_ms,          -- TIMER 값 단위는 피코초(1e-12초)
       ROUND(MAX_TIMER_WAIT/1e9, 2) AS max_ms,
       ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_sec,
       ROUND(SUM_ROWS_EXAMINED/COUNT_STAR) AS rows_exam_per_exec,
       ROUND(SUM_ROWS_SENT/COUNT_STAR) AS rows_sent_per_exec,
       SUM_NO_INDEX_USED AS no_index_used,
       LEFT(DIGEST_TEXT, 160) AS query
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME LIKE 'thock_%'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 40;

-- 전체 문장의 실행 시간 분포 (슬로우 기준 100ms 의 근거로 사용)
SELECT CASE WHEN BUCKET_TIMER_HIGH <= 1e9  THEN '<= 1ms'
            WHEN BUCKET_TIMER_HIGH <= 1e10 THEN '<= 10ms'
            WHEN BUCKET_TIMER_HIGH <= 1e11 THEN '<= 100ms'
            WHEN BUCKET_TIMER_HIGH <= 1e12 THEN '<= 1s' ELSE '> 1s' END AS latency,
       SUM(COUNT_BUCKET) AS statements
FROM performance_schema.events_statements_histogram_global
GROUP BY latency ORDER BY MIN(BUCKET_TIMER_HIGH);
```

`rows_exam_per_exec` 가 `rows_sent_per_exec` 보다 수천~수만 배 크면 개선 대상이다.

## 3. 실행 계획: EXPLAIN

```sql
SHOW INDEX FROM thock_market_db.market_orders;          -- 지금 있는 인덱스
EXPLAIN SELECT ... ;                                     -- 계획만 (실행 안 함)
EXPLAIN ANALYZE SELECT ... ;                             -- 실제 실행 + 단계별 실제 시간/행 수 (8.0.18+)
EXPLAIN FORMAT=TREE SELECT ... ;                         -- 계획을 트리로 (실행 안 함)
```

| 컬럼 | 보는 것 |
|---|---|
| `type` | 접근 방식. 좋은 순: `const`/`eq_ref`(1건) → `ref`(인덱스 동등) → `range`(인덱스 범위) → `index`(인덱스 전체) → `ALL`(테이블 전체) |
| `key` | 실제로 고른 인덱스. `NULL` 이면 인덱스 안 씀 |
| `rows` | 읽을 것으로 **예상**한 행 수 |
| `filtered` | 읽은 행 중 조건을 통과할 것으로 예상한 비율(%). 낮을수록 "읽고 버리는" 행이 많다 |
| `Extra` | `Using index`(커버링, 테이블 안 읽음) / `Using index condition`(인덱스 단계에서 먼저 거름) / `Using where`(행을 읽은 뒤 거름) / `Using temporary`(임시 테이블) / `Using filesort`(인덱스 순서를 못 써서 따로 정렬) / `Backward index scan`(인덱스를 역순으로 읽음) |

`EXPLAIN ANALYZE` 는 트리로 나오고 **안쪽(아래)부터** 실행된다.

```
-> Aggregate: count(o1_0.id)  (cost=124297 rows=1) (actual time=642..642 rows=1 loops=1)
    -> Filter: (o1_0.state = 'CONFIRMED')  (cost=114039 rows=102587) (actual time=5.11..615 rows=709990 loops=1)
        -> Table scan on o1_0  (cost=114039 rows=1.03e+6) (actual time=0.52..521 rows=1.03e+6 loops=1)
```

- `cost`, 앞쪽 `rows`: 옵티마이저 예상 / `actual time=A..B`: 첫 행까지 A ms, 마지막 행까지 B ms
- 뒤쪽 `rows`, `loops`: 실제 반환 행과 반복 횟수 (조인 안쪽은 `rows × loops` 가 실제 처리량)

**함정: `EXPLAIN ANALYZE` 의 시간은 "실행 단계"만 잰다.** FULLTEXT `count(*)` 처럼 옵티마이저 단계에서 답을 구하는 쿼리는
`Select tables optimized away` 로 0ms 처럼 보이지만, 실제로는 1.2초가 걸렸다(H3). 그래서 시간은 아래 방법으로 따로 쟀다.

## 4. 실행 시간 재기: events_statements_history

같은 커넥션에서 쿼리를 6번(첫 번은 워밍업) 실행하고, 서버가 각 문장에 쓴 시간(`TIMER_WAIT`)을 읽어 중앙값을 냈다.
슬로우 로그의 `Query_time` 과 같은 기준(파싱~최적화~실행~결과 전송)이다. `scripts/explain.sh` 가 이 방식이다.

```sql
SELECT ... ;   -- 6번 반복
SELECT ROUND(TIMER_WAIT/1e9, 3) AS ms
FROM performance_schema.events_statements_history
WHERE THREAD_ID = PS_CURRENT_THREAD_ID()            -- 내 커넥션
  AND SQL_TEXT NOT LIKE '%events_statements_history%'
ORDER BY EVENT_ID DESC LIMIT 5;
```

첫 실행을 버리는 이유: 처음에는 데이터가 메모리(버퍼 풀)에 없어서 디스크에서 읽느라 느리다.

## 5. 인덱스 만들기 / 전후 비교

```sql
CREATE INDEX idx_orders_state_request_payment ON market_orders (state, request_payment_date);
DROP INDEX idx_sales_log_status ON sales_log;

-- 인덱스를 지우지 않고 옵티마이저에서만 숨기기 (8.0+). 같은 데이터로 전/후를 비교할 때 썼다.
ALTER TABLE market_orders ALTER INDEX idx_orders_state_request_payment INVISIBLE;
ALTER TABLE market_orders ALTER INDEX idx_orders_state_request_payment VISIBLE;

-- 인덱스 크기 (페이지 수 × 페이지 크기)
SELECT index_name, ROUND(stat_value * @@innodb_page_size / 1024 / 1024) AS mb
FROM mysql.innodb_index_stats
WHERE database_name = 'thock_product_db' AND table_name = 'products' AND stat_name = 'size';

-- 통계 갱신 (대량 적재 직후 예상 rows 가 실제와 크게 다를 때)
ANALYZE TABLE thock_market_db.market_orders;

-- 특정 인덱스를 강제로 쓰게 하기 (H3 지연 조인에서 사용)
SELECT id FROM products FORCE INDEX (idx_products_name) WHERE name LIKE '%M123%';
```

보조(secondary) 인덱스 추가는 InnoDB online DDL 이라 테이블 쓰기를 막지 않는다. 이번 데이터(50만~120만 행)에서 1개당 1~5초 걸렸다.
반대로 첫 FULLTEXT 인덱스는 테이블을 다시 만들어서(26초) 그동안 쓰기가 막힌다.

## 6. 측정 스크립트 (docs/query-tuning/scripts)

| 스크립트 | 하는 일 |
|---|---|
| `slowlog.sh on <label>` / `off` | 위 1번 설정 + 2번 통계 초기화 |
| `services.sh start <코드 루트> <로그 폴더>` / `stop` | product/market/payment/settlement 를 local 프로파일로 기동 |
| `collect.sh <label> <폴더>` | 슬로우 로그 복사, mysqldumpslow 요약, digest 상위 40, 히스토그램 저장 |
| `reconcile.sh backup|run|restore <날짜>` | 대사 배치를 API 로 실행하고 시간 측정, 전/후 같은 조건으로 되돌림 |
| `explain.sh <db> "<SQL>"` | EXPLAIN + EXPLAIN ANALYZE + 서버 실행 시간 중앙값 |
| `measure-all.sh before|after` | 대상 쿼리 전체를 explain.sh 로 측정해 `results/explain/` 에 저장 |
| `toggle-indexes.sh before|after` | 새 인덱스를 INVISIBLE/VISIBLE 로 전환 |
| `h3-ladder.sh` / `fulltext-equivalence.sh` | H3 검색 방식 비교 실험 |
