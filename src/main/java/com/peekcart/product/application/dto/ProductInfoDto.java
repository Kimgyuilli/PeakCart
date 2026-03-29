package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

public record ProductInfoDto(
        Long id,
        Long categoryId,
        String categoryName,
        String name,
        String description,
        long price,
        String imageUrl,
        String status
) {
    public static ProductInfoDto of(Product product) {
        return new ProductInfoDto(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name());
    }
}
