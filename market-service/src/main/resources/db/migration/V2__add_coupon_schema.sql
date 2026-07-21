CREATE TABLE market_coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value BIGINT NOT NULL,
    minimum_order_amount BIGINT NOT NULL,
    maximum_discount_amount BIGINT,
    total_quantity INT NOT NULL,
    issued_quantity INT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    active BIT NOT NULL,
    version BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_coupon_code UNIQUE (code)
);

CREATE TABLE market_member_coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    member_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    used_at DATETIME(6),
    used_order_number VARCHAR(50),
    version BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_coupon UNIQUE (member_id, coupon_id),
    CONSTRAINT fk_member_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES market_coupons (id)
);

ALTER TABLE market_orders ADD COLUMN coupon_id BIGINT NULL;
ALTER TABLE market_orders ADD COLUMN coupon_discount_amount BIGINT NOT NULL DEFAULT 0;
