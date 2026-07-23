package com.thock.back.product.in.dto;

import com.thock.back.product.domain.entity.Product;

public record ProductListResponse (
        Long id,
        String name,
        String imageUrl,
        Long price,
        Long originalPrice,
        Long salePrice,
        String category,
        String nickname
) {
    public ProductListResponse(Product product) {
        this(
                product.getId(),
                product.getName(),
                product.getImageUrl(),
                product.getPrice(),
                product.getPrice(),
                product.getSalePrice(),
                product.getCategory().name(),
                "판매자 " + product.getSellerId()
        );
    }
}
