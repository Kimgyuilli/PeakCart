package com.peekcart.product.presentation.dto.response;

import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.domain.model.Product;

/**
 * 상품 상세 응답 DTO.
 */
public record ProductDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String name,
        String description,
        long price,
        String imageUrl,
        String status,
        int stock
) {

    public static ProductDetailResponse from(ProductDetailDto dto) {
        Product product = dto.product();
        return new ProductDetailResponse(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name(),
                dto.stock());
    }
}
