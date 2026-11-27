-- payment-service / settlement-service 는 Flyway 가 없다.
--  * 기본/로컬(ddl-auto: update): 엔티티 @Index 를 보고 기동 시 Hibernate 가 새 인덱스를 만든다(기존 인덱스 삭제는 안 함).
--  * 운영(settlement prod: ddl-auto: validate): 인덱스를 만들지도 검증하지도 않으므로 아래 DDL 을 수동으로 적용한다.

-- H2: 결제 조회 (findByOrderId)
CREATE INDEX idx_payment_order_id ON thock_payment_db.payment_payments (order_id);

-- M6: 정산 후보 / 대사 잔여건 조회
CREATE INDEX idx_sales_log_status_settle ON thock_settlement_db.sales_log (reconciliation_status, daily_settlement_id, confirmed_at);
CREATE INDEX idx_sales_log_status_snapshot ON thock_settlement_db.sales_log (reconciliation_status, snapshot_at);
-- 위 두 인덱스가 reconciliation_status 선두라 단일 상태 인덱스는 중복 -> 쓰기 비용만 늘리므로 삭제
DROP INDEX idx_sales_log_status ON thock_settlement_db.sales_log;
