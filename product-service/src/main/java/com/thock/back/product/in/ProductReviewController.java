package com.thock.back.product.in;

import com.thock.back.global.security.AuthUser;
import com.thock.back.global.security.AuthenticatedUser;
import com.thock.back.product.app.ProductReviewService;
import com.thock.back.product.in.dto.ProductReviewCreateRequest;
import com.thock.back.product.in.dto.ProductReviewResponse;
import com.thock.back.product.in.dto.ProductReviewSummaryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductReviewController {

    private final ProductReviewService productReviewService;

    @GetMapping("/{productId}/reviews")
    public ResponseEntity<Page<ProductReviewResponse>> getReviews(
            @PathVariable @Positive Long productId,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(productReviewService.getReviews(productId, pageable));
    }

    @PostMapping("/{productId}/reviews")
    public ResponseEntity<ProductReviewResponse> createReview(
            @PathVariable @Positive Long productId,
            @RequestBody @Valid ProductReviewCreateRequest request,
            @AuthUser AuthenticatedUser user
    ) {
        ProductReviewResponse response = productReviewService.createReview(productId, user.memberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{productId}/reviews/{reviewId}")
    public ResponseEntity<Void> deleteReview(
            @PathVariable @Positive Long productId,
            @PathVariable @Positive Long reviewId,
            @AuthUser AuthenticatedUser user
    ) {
        productReviewService.deleteReview(productId, reviewId, user.memberId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reviews/summaries")
    public ResponseEntity<List<ProductReviewSummaryResponse>> getReviewSummaries(
            @RequestParam
            @NotEmpty
            @Size(max = 100)
            List<@Positive Long> productIds
    ) {
        return ResponseEntity.ok(productReviewService.getSummaries(productIds));
    }
}
