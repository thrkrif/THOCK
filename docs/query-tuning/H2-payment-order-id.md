# H2. 결제 조회 `findByOrderId` — payment_payments.order_id 인덱스

## 한 줄 요약

결제 확정/환불/취소 경로마다 호출되는 `findByOrderId` 가 결제 102만 행을 풀스캔하고 있었다. `order_id` 인덱스로 **296ms → 0.08ms**.

## 1. 증상

이 쿼리는 k6 부하로 직접 호출하지 못했다(토스 결제 승인 API, Kafka 취소 이벤트 경로라서). 그래서 코드에서 호출 지점을 세고 쿼리를 직접 쟀다.

```bash
grep -rn "paymentRepository.findByOrderId" payment-service/src/main
# PaymentConfirmAndRefundUseCase: 4곳 (확정, 토스 확정, 환불, 취소)
# PaymentFacade.canceledPayment: 1곳 (취소 이벤트마다 PG 결제 여부 확인)
```

주문 1건의 결제 확정/취소 흐름에서 1~2번씩 호출된다.

## 2. 원인 분석

```sql
SHOW INDEX FROM thock_payment_db.payment_payments;   -- PRIMARY, buyer_id(FK) 뿐
EXPLAIN SELECT ... FROM payment_payments p1_0 WHERE p1_0.order_id = 'PERF-500000';
```

| type | key | rows | Extra |
|---|---|---|---|
| ALL | NULL | 1,023,760 | Using where |

```
-> Filter: (p1_0.order_id = 'PERF-500000')  (actual time=324..611 rows=1 loops=1)
    -> Table scan on p1_0  (actual time=1.37..532 rows=1.03e+6 loops=1)
```

1건을 찾으려고 103만 건을 읽는다. `order_id` 는 VARCHAR(255) 인데 인덱스가 없었다.

## 3. 해결

payment-service 는 **Flyway 가 없고** `ddl-auto: update` 다. 엔티티에 인덱스를 선언하면 기동할 때 Hibernate 가 만든다.

```java
@Table(name = "payment_payments",
        indexes = @Index(name = "idx_payment_order_id", columnList = "order_id"))
public class Payment extends BaseIdAndTime {
```

운영처럼 `validate` 로 도는 환경이 생기면 `sql/manual-ddl-payment-settlement.sql` 을 수동으로 적용한다.

### UNIQUE 로 하지 않은 이유 — 실제 데이터에서 중복 발견

```sql
SELECT COUNT(*), COUNT(DISTINCT order_id) FROM thock_payment_db.payment_payments;
-- 28457, 28456  → 1건 중복
SELECT id, order_id, status, created_at FROM payment_payments
WHERE order_id IN (SELECT order_id FROM payment_payments GROUP BY order_id HAVING COUNT(*) > 1);
-- 5739 | ORDER-20260222-3CEA5BC19C9E | REQUESTED | 2026-02-22 13:50:46
-- 5740 | ORDER-20260222-3CEA5BC19C9E | REQUESTED | 2026-02-22 13:52:00   (74초 뒤 같은 주문으로 결제 요청이 또 저장됨)
```

UNIQUE 인덱스는 이 데이터가 있으면 생성 자체가 실패한다. 또 이 주문으로 `findByOrderId` 를 부르면
`Optional` 인데 2건이 나와 `IncorrectResultSizeDataAccessException` 이 난다. **결제 요청이 중복 저장되는 버그가 있었다는 뜻**이라
별도 과제로 남긴다(중복 정리 → 결제 생성 멱등성 확인 → UNIQUE 전환). 이번에는 일반 인덱스만 추가했다.

## 4. 재측정

| | 전 | 후 |
|---|---|---|
| `findByOrderId` 서버 실행 시간 | 296ms | **0.08ms** |
| 계획 | `ALL`, rows 102만 | `ref` (`idx_payment_order_id`), rows 1 |

```
-> Index lookup on p1_0 using idx_payment_order_id (order_id='PERF-500000')  (actual time=0.682..0.683 rows=1 loops=1)
```

인덱스 생성 시간: 103만 행에 2초 (online DDL 이라 쓰기를 막지 않음).

## 사용한 명령

```sql
SHOW INDEX FROM thock_payment_db.payment_payments;
EXPLAIN ANALYZE SELECT ... FROM payment_payments p1_0 WHERE p1_0.order_id = 'PERF-500000';
SELECT COUNT(*), COUNT(DISTINCT order_id) FROM payment_payments;   -- UNIQUE 가능 여부 확인
CREATE INDEX idx_payment_order_id ON payment_payments (order_id);
```
