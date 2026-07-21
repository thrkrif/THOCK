CREATE TABLE IF NOT EXISTS market_shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    order_item_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    carrier VARCHAR(40) NOT NULL,
    tracking_number VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    shipped_at DATETIME(6),
    delivered_at DATETIME(6),
    version BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_shipment_order_item UNIQUE (order_item_id),
    CONSTRAINT fk_market_shipment_order_item FOREIGN KEY (order_item_id) REFERENCES market_order_items (id)
);
