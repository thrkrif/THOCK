-- H1: 주문 상태(state) 조건 조회용 인덱스
--  * 메트릭 수집기 GROUP BY state 집계 -> (state, ...) 인덱스만 읽는 커버링 스캔
--  * OrderTimeoutScheduler: state = 'PENDING_PAYMENT' AND request_payment_date < ?
--  * OrderCleanupScheduler: state = 'CANCELLED' AND cancel_date < ?
-- 보조 인덱스 추가는 InnoDB online DDL(ALGORITHM=INPLACE, LOCK=NONE)이라 쓰기를 막지 않는다.
CREATE INDEX idx_orders_state_request_payment ON market_orders (state, request_payment_date);
CREATE INDEX idx_orders_state_cancel_date ON market_orders (state, cancel_date);
