package com.thock.back.product.out;

import com.thock.back.product.domain.entity.ProductReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    Page<ProductReview> findByProductId(Long productId, Pageable pageable);

    Optional<ProductReview> findByIdAndProductId(Long reviewId, Long productId);

    @Query("""
            select r.productId as productId,
                   avg(r.rating) as averageRating,
                   count(r.id) as reviewCount
              from ProductReview r
             where r.productId in :productIds
             group by r.productId
            """)
    List<ProductReviewSummaryProjection> summarizeByProductIds(@Param("productIds") Collection<Long> productIds);
}
