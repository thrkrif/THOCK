CREATE TABLE product_reviews
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    product_id  BIGINT        NOT NULL,
    member_id   BIGINT        NOT NULL,
    rating      DECIMAL(2, 1) NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_review_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT chk_product_review_rating
        CHECK (rating >= 0.5 AND rating <= 5.0 AND MOD(rating * 10, 5) = 0),
    KEY idx_product_reviews_product_created (product_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
