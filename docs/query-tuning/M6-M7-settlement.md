# M6. 정산 후보/대사 잔여건 조회 인덱스 / M7. 대사 배치 N+1

## M6. sales_log 복합 인덱스

### 증상과 원인

정산 배치가 쓰는 정산 후보 조회:

```sql
SELECT ... FROM sales_log WHERE reconciliation_status = 'MATCH' AND confirmed_at IS NOT NULL AND daily_settlement_id IS NULL;
```

```
-> Filter: ((confirmed_at is not null) and (daily_settlement_id is null))  (actual time=13.5..2109 rows=5455 loops=1)
    -> Index lookup on sl1_0 using idx_sales_log_status (reconciliation_status='MATCH')  (actual time=7.51..2066 rows=970018 loops=1)
```

- `idx_sales_log_status (reconciliation_status)` 로 MATCH 를 찾지만 **MATCH 가 97만 건**(전체의 97%)이다
- 나머지 조건은 행을 하나씩 읽은 뒤에 거른다 → 5,455건을 얻으려고 97만 건을 랜덤으로 읽음. 이력이 쌓일수록 비례해서 느려진다
- 대사 잔여건 조회(`snapshot_at BETWEEN ? AND ? AND status = 'PENDING'`)도 `snapshot_at` 범위를 인덱스에서 못 써서 PENDING 전체(약 2만 건)를 읽는다

### 해결

settlement-service 는 Flyway 가 없다. 엔티티에 선언하고(`ddl-auto: update` 환경에서 기동 시 생성), 운영용 DDL 을 따로 남겼다.

```java
@Index(name = "idx_sales_log_status_settle",   columnList = "reconciliation_status, daily_settlement_id, confirmed_at"),
@Index(name = "idx_sales_log_status_snapshot", columnList = "reconciliation_status, snapshot_at")
```

- 컬럼 순서: 등호 조건(`status = 'MATCH'`, `daily_settlement_id IS NULL`)을 앞에, 범위 조건(`confirmed_at IS NOT NULL`)을 마지막에.
  범위 조건 뒤 컬럼은 인덱스로 좁힐 수 없기 때문
- 기존 `idx_sales_log_status` 는 두 인덱스가 모두 `reconciliation_status` 로 시작해 **중복**이다. 쓰기 비용만 늘려서 삭제했다
  (`ddl-auto: update` 는 인덱스를 지우지 않으므로 `sql/manual-ddl-payment-settlement.sql` 의 `DROP INDEX` 를 수동 실행)

### 결과

| | 전 | 후 |
|---|---|---|
| 정산 후보 조회 | 1,170ms (97만 건 읽음) | **33ms** (`range`, 5,455건만) |
| 대사 잔여건 조회 | 66ms | **0.05ms** |

## M7. 대사 배치 `RunReconciliationUseCase` N+1

### 증상

하루치 대사(`POST /api/v1/settlements/reconciliation/manual/run-reconciliation?targetDate=2026-10-15`, PG 2,723건)가 **11.6초** 걸렸다.
슬로우 로그에는 안 잡혔다. 쿼리 하나하나는 빨랐기 때문이다. digest 통계로 보니:

| 쿼리 | 실행 횟수 | 평균 |
|---|---|---|
| `SELECT ... FROM sales_log WHERE order_no = ? AND transaction_type = ?` | **2,723** | 0.43ms |

인덱스(`idx_sales_log_order_no`)도 잘 탄다(0.07ms). 문제는 개수다. PG 건마다 1번(타입이 다르면 `findByOrderNo` 로 1번 더) 조회해서,
DB 시간은 1.2초인데 왕복·JPA 처리까지 합치면 11초가 된다. 슬로우 로그만 보면 놓치는 유형이라 digest 의 `exec_count` 를 같이 봐야 한다.

### 해결 (비즈니스 로직 변경)

```java
// 변경 전: PG 키(주문번호_상태)마다
List<SalesLog> internalLogs = salesLogRepository.findByOrderNoAndTransactionType(merchantUid, targetType);
...
List<SalesLog> sameOrderLogs = salesLogRepository.findByOrderNo(merchantUid);   // 타입 불일치일 때 한 번 더

// 변경 후: 이날 PG 에 나온 주문번호를 IN 으로 한 번에(1,000개씩) 읽어 주문번호별로 묶는다
Map<String, List<SalesLog>> logsByOrderNo = loadSalesLogsByOrderNo(merchantUids);   // findByOrderNoIn(chunk)
...
List<SalesLog> sameOrderLogs = logsByOrderNo.getOrDefault(merchantUid, List.of());
List<SalesLog> internalLogs = sameOrderLogs.stream()
        .filter(salesLog -> salesLog.getTransactionType() == targetType)
        .toList();
```

- 동작이 같다는 근거: `findByOrderNoAndTransactionType(no, type)` 결과 = `findByOrderNo(no)` 결과를 `type` 으로 거른 것
- 같은 김에 로그용 대표 PG 건을 찾을 때 키마다 PG 목록 전체를 다시 훑던 것(2,723 × 2,723 비교)도 키별 맵으로 바꿨다
- 테스트: 기존 12개 시나리오(일치, 1:N 합산, 금액/상태 불일치, PG 만/내부만 있음 등)는 IN 조회 스텁으로 바꿔 그대로 통과,
  "PG 3건이어도 SalesLog 조회는 1번" 검증 테스트를 추가했다

### 결과

| | 전 | 후 |
|---|---|---|
| 대사 API 1회 (PG 2,723건) | **11.6초** | **0.65초** |
| sales_log 조회 횟수 | 2,723번 | 3번 (1,000개씩 IN, 평균 144ms) |
| 대사 결과 | 2,723건 일치 | 2,723건 일치 (같음) |

## 사용한 명령

```sql
EXPLAIN ANALYZE SELECT ... FROM sales_log WHERE reconciliation_status = 'MATCH' AND confirmed_at IS NOT NULL AND daily_settlement_id IS NULL;
-- N+1 은 실행 횟수로 찾는다
SELECT COUNT_STAR, ROUND(AVG_TIMER_WAIT/1e9, 2) AS avg_ms, LEFT(DIGEST_TEXT, 120)
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'thock_settlement_db' ORDER BY COUNT_STAR DESC LIMIT 10;
```

```bash
./scripts/reconcile.sh backup 2026-10-15    # 대사가 바꾸는 상태를 백업
./scripts/reconcile.sh run 2026-10-15       # PG 원장 생성 → 대사, 소요 시간 출력
./scripts/reconcile.sh restore 2026-10-15   # 전/후를 같은 조건으로 돌리기 위해 복원
```
