package com.thock.back.product.out;

public interface ProductReviewSummaryProjection {
    Long getProductId();
    Double getAverageRating();
    Long getReviewCount();
}
