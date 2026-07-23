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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductReviewRepository productReviewRepository;

    @InjectMocks
    private ProductReviewService productReviewService;

    @Test
    void createsAReviewForAnExistingProduct() {
        ProductReviewCreateRequest request = new ProductReviewCreateRequest(new BigDecimal("4.5"), "Great keyboard");
        when(productRepository.existsById(10L)).thenReturn(true);
        when(productReviewRepository.save(org.mockito.ArgumentMatchers.any(ProductReview.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductReviewResponse response = productReviewService.createReview(10L, 20L, request);

        assertThat(response.productId()).isEqualTo(10L);
        assertThat(response.authorId()).isEqualTo(20L);
        assertThat(response.rating()).isEqualByComparingTo("4.5");
        assertThat(response.content()).isEqualTo("Great keyboard");
    }

    @Test
    void rejectsRatingsThatAreNotHalfPointSteps() {
        ProductReviewCreateRequest request = new ProductReviewCreateRequest(new BigDecimal("4.3"), "Invalid rating");
        when(productRepository.existsById(10L)).thenReturn(true);

        assertThatThrownBy(() -> productReviewService.createReview(10L, 20L, request))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void returnsBatchSummariesIncludingProductsWithoutReviews() {
        ProductReviewSummaryProjection projection = mock(ProductReviewSummaryProjection.class);
        when(projection.getProductId()).thenReturn(10L);
        when(projection.getAverageRating()).thenReturn(4.25);
        when(projection.getReviewCount()).thenReturn(4L);
        when(productReviewRepository.summarizeByProductIds(List.of(10L, 11L)))
                .thenReturn(List.of(projection));

        List<ProductReviewSummaryResponse> summaries = productReviewService.getSummaries(List.of(10L, 11L, 10L));

        assertThat(summaries).containsExactly(
                new ProductReviewSummaryResponse(10L, 4.25, 4L),
                new ProductReviewSummaryResponse(11L, 0.0, 0L)
        );
    }

    @Test
    void onlyTheAuthorCanDeleteAReview() {
        ProductReview review = new ProductReview(10L, 20L, new BigDecimal("5.0"), "Excellent");
        when(productReviewRepository.findByIdAndProductId(30L, 10L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> productReviewService.deleteReview(10L, 30L, 99L))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_REVIEW_FORBIDDEN);
    }

    @Test
    void authorCanDeleteAReview() {
        ProductReview review = new ProductReview(10L, 20L, new BigDecimal("5.0"), "Excellent");
        when(productReviewRepository.findByIdAndProductId(30L, 10L)).thenReturn(Optional.of(review));

        productReviewService.deleteReview(10L, 30L, 20L);

        verify(productReviewRepository).delete(review);
    }
}
