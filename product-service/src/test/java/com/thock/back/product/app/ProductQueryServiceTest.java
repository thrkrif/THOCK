package com.thock.back.product.app;

import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.in.dto.ProductListResponse;
import com.thock.back.product.out.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductQueryService productQueryService;

    @Test
    void keywordSearchUsesDatabasePaginationAndReturnsBothPrices() {
        Pageable pageable = PageRequest.of(2, 12);
        Product product = Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name("Mechanical Keyboard")
                .price(100_000L)
                .salePrice(80_000L)
                .stock(10)
                .build();
        // 결과가 적은 검색어(25건)는 이름 인덱스 지연 조인으로 목록을 읽는다 (H3)
        when(productRepository.countByNameContaining("Keyboard")).thenReturn(25L);
        when(productRepository.findByNameLikeUsingNameIndex("Keyboard", 12, 24L)).thenReturn(List.of(product));

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("  Keyboard  ", pageable);

        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getContent()).singleElement().satisfies(response -> {
            assertThat(response.originalPrice()).isEqualTo(100_000L);
            assertThat(response.salePrice()).isEqualTo(80_000L);
            assertThat(response.category()).isEqualTo("KEYBOARD");
        });
        verify(productRepository, never()).findByNameContainingOrderByIdDesc(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void denseKeywordReadsListInPrimaryKeyOrderWithoutAnotherCount() {
        // 결과가 많은 검색어는 PK 역순으로 읽다가 LIMIT 에서 멈추는 쿼리를 쓴다
        Pageable pageable = PageRequest.of(0, 12);
        when(productRepository.countByNameContaining("Keychron")).thenReturn(ProductQueryService.DENSE_MIN_HITS);
        when(productRepository.findByNameContainingOrderByIdDesc("Keychron", PageRequest.of(0, 12))).thenReturn(List.of());

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("Keychron", pageable);

        assertThat(result.getTotalElements()).isEqualTo(ProductQueryService.DENSE_MIN_HITS);
        verify(productRepository, never()).findByNameLikeUsingNameIndex(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
        verify(productRepository, never()).findByNameContaining(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keywordWithNoMatchSkipsTheListQuery() {
        Pageable pageable = PageRequest.of(0, 12);
        when(productRepository.countByNameContaining("Cherry")).thenReturn(0L);

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("Cherry", pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(productRepository, never()).findByNameContainingOrderByIdDesc(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(productRepository, never()).findByNameLikeUsingNameIndex(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void likeWildcardsInKeywordAreEscapedForNativeQuery() {
        assertThat(ProductQueryService.escapeLike("75%")).isEqualTo("75\\%");
        assertThat(ProductQueryService.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(ProductQueryService.escapeLike("c\\d")).isEqualTo("c\\\\d");
    }

    @Test
    void blankKeywordReturnsAnEmptyPageWithoutQueryingTheDatabase() {
        Pageable pageable = PageRequest.of(0, 12);

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("  ", pageable);

        assertThat(result).isEmpty();
        verify(productRepository, never()).countByNameContaining(org.mockito.ArgumentMatchers.anyString());
    }
}
