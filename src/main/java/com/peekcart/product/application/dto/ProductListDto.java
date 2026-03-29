package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

public record ProductListDto(
        Long id,
        String name,
        long price,
        String imageUrl,
        String status
) {
    public static ProductListDto of(Product product) {
        return new ProductListDto(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name());
    }
}
