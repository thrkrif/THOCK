-- M4: 판매자 배송 목록 (WHERE seller_id = ? ORDER BY created_at DESC)
-- seller_id 인덱스가 없어 배송 테이블 전체를 읽고 정렬했다.
CREATE INDEX idx_shipments_seller_created ON market_shipments (seller_id, created_at);
