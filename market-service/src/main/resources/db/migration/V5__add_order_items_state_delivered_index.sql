-- M3: 자동 구매 확정 배치 (WHERE state = 'DELIVERED' AND delivered_at < ?)
-- 기존 idx_seller_state(seller_id, state)는 선두 컬럼이 seller_id 라 이 조건에 쓸 수 없어 풀스캔.
CREATE INDEX idx_order_items_state_delivered ON market_order_items (state, delivered_at);
