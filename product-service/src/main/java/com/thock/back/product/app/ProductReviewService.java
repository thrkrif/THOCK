package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.domain.entity.ProductReview;
import com.thock.back.product.in.dto.ProductReviewCreateRequest;
import com.thock.back.product.in.dto.ProductReviewResponse;
import com.thock.back.product.in.dto.ProductReviewSummaryResponse;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.product.out.ProductReviewRepository;
import com.thock.back.product.out.ProductReviewSummaryProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductReviewService {

    private static final BigDecimal HALF_POINT = new BigDecimal("0.5");

    private final ProductRepository productRepository;
    private final ProductReviewRepository productReviewRepository;

    public Page<ProductReviewResponse> getReviews(Long productId, Pageable pageable) {
        return productReviewRepository.findByProductId(productId, pageable)
                .map(ProductReviewResponse::from);
    }

    @Transactional
    public ProductReviewResponse createReview(
            Long productId,
            Long memberId,
            ProductReviewCreateRequest request
    ) {
        if (!productRepository.existsById(productId)) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (request.rating().remainder(HALF_POINT).compareTo(BigDecimal.ZERO) != 0) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        ProductReview review = new ProductReview(
                productId,
                memberId,
                request.rating(),
                request.content()
        );
        return ProductReviewResponse.from(productReviewRepository.save(review));
    }

    @Transactional
    public void deleteReview(Long productId, Long reviewId, Long memberId) {
        ProductReview review = productReviewRepository.findByIdAndProductId(reviewId, productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND));

        if (!review.getMemberId().equals(memberId)) {
            throw new CustomException(ErrorCode.PRODUCT_REVIEW_FORBIDDEN);
        }
        productReviewRepository.delete(review);
    }

    public List<ProductReviewSummaryResponse> getSummaries(List<Long> requestedProductIds) {
        List<Long> productIds = requestedProductIds.stream().distinct().toList();
        if (productIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductReviewSummaryResponse> summaryMap = new LinkedHashMap<>();
        productIds.forEach(productId -> summaryMap.put(productId, ProductReviewSummaryResponse.empty(productId)));

        for (ProductReviewSummaryProjection projection : productReviewRepository.summarizeByProductIds(productIds)) {
            summaryMap.put(
                    projection.getProductId(),
                    new ProductReviewSummaryResponse(
                            projection.getProductId(),
                            projection.getAverageRating(),
                            projection.getReviewCount()
                    )
            );
        }
        return List.copyOf(summaryMap.values());
    }
}
