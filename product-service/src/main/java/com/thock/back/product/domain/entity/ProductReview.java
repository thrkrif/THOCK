package com.thock.back.product.domain.entity;

import com.thock.back.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "product_reviews",
        indexes = {
                @Index(name = "idx_product_reviews_product_created", columnList = "product_id, created_at, id"),  // V4
                @Index(name = "idx_product_reviews_product_rating", columnList = "product_id, rating")            // 리뷰 요약 커버링 (V8)
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReview extends BaseIdAndTime {

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(nullable = false, length = 1000)
    private String content;

    public ProductReview(Long productId, Long memberId, BigDecimal rating, String content) {
        this.productId = productId;
        this.memberId = memberId;
        this.rating = rating;
        this.content = content.trim();
    }
}
