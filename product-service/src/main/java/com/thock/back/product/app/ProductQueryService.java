package com.thock.back.product.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.dto.ProductDetailResponse;
import com.thock.back.product.in.dto.ProductListResponse;
import com.thock.back.product.in.dto.internal.ProductInternalResponse;
import com.thock.back.product.out.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {
    private final ProductRepository productRepository;

    public Page<ProductListResponse> searchProductsByCategory(Category category, Pageable pageable) {
        return productRepository.findByCategory(category, pageable)
                .map(ProductListResponse::new);
    }

    public ProductDetailResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));
        return new ProductDetailResponse(product);
    }

    /**
     * [H3] 상품명 검색. 응답(Page)은 그대로 두고 쿼리 실행 방식만 결과 건수에 맞춰 고른다.
     * 정렬은 id DESC 고정 (프론트도 id,desc 만 보낸다).
     *  1) count 먼저 (Page 응답에 어차피 필요). 이름 인덱스 커버링 스캔.
     *  2) 0건이면 목록 쿼리를 생략한다.
     *  3) 많으면(DENSE_MIN_HITS 이상) PK 역순 스캔: 일치하는 행이 촘촘해 몇백 행만 읽고 멈춘다.
     *  4) 적으면 이름 인덱스 지연 조인: 일치하는 id 만 찾아 정렬 후 그 행만 읽는다. 결과 건수와 무관하게 일정.
     * 측정 근거: docs/query-tuning/H3-product-search.md
     */
    public Page<ProductListResponse> searchProductsByKeyword(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            return Page.empty(pageable);
        }
        String trimmed = keyword.trim();
        Pageable byIdDesc = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));

        long total = productRepository.countByNameContaining(trimmed);
        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), byIdDesc, total);
        }

        List<Product> products = total >= DENSE_MIN_HITS
                ? productRepository.findByNameContainingOrderByIdDesc(trimmed, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                : productRepository.findByNameLikeUsingNameIndex(escapeLike(trimmed), pageable.getPageSize(), pageable.getOffset());

        return new PageImpl<>(products, byIdDesc, total).map(ProductListResponse::new);
    }

    // 실측(상품 50만 건): 결과 6.2만 건 이상인 검색어는 PK 역순 스캔이 0.1ms, 1.1만 건 이하는 300~450ms.
    // 그 사이 값으로 2만 건을 기준으로 잡았다. 상품 수가 크게 늘면 다시 재서 조정한다.
    static final long DENSE_MIN_HITS = 20_000;

    // 네이티브 LIKE 에서 사용자가 입력한 % _ \ 가 와일드카드로 해석되지 않게 한다.
    // (Spring Data 의 Containing 도 같은 방식으로 이스케이프한다)
    static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<ProductInternalResponse> getProductsByIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findAllByIdIn(productIds).stream()
                .map(ProductInternalResponse::new)
                .toList();
    }

    public Page<ProductListResponse> getMyProducts(Long sellerId, Pageable pageable) {
        return productRepository.findBySellerId(sellerId, pageable)
                .map(ProductListResponse::new);
    }
}
