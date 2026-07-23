package com.thock.back.product.in.dto;

import com.thock.back.product.domain.entity.ProductReview;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductReviewResponse(
        Long id,
        Long productId,
        BigDecimal rating,
        String content,
        Long authorId,
        String authorName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProductReviewResponse from(ProductReview review) {
        return new ProductReviewResponse(
                review.getId(),
                review.getProductId(),
                review.getRating(),
                review.getContent(),
                review.getMemberId(),
                "회원",
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
