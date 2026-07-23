package com.thock.back.product.in.dto;

public record ProductReviewSummaryResponse(
        Long productId,
        double averageRating,
        long reviewCount
) {
    public static ProductReviewSummaryResponse empty(Long productId) {
        return new ProductReviewSummaryResponse(productId, 0.0, 0L);
    }
}
