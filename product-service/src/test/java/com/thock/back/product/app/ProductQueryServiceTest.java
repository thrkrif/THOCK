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
        when(productRepository.findByNameContaining("Keyboard", pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 25));

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("  Keyboard  ", pageable);

        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getContent()).singleElement().satisfies(response -> {
            assertThat(response.originalPrice()).isEqualTo(100_000L);
            assertThat(response.salePrice()).isEqualTo(80_000L);
            assertThat(response.category()).isEqualTo("KEYBOARD");
        });
        verify(productRepository).findByNameContaining("Keyboard", pageable);
    }

    @Test
    void blankKeywordReturnsAnEmptyPageWithoutQueryingTheDatabase() {
        Pageable pageable = PageRequest.of(0, 12);

        Page<ProductListResponse> result = productQueryService.searchProductsByKeyword("  ", pageable);

        assertThat(result).isEmpty();
        verify(productRepository, never()).findByNameContaining(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
