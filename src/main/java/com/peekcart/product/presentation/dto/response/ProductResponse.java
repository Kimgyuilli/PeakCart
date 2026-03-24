package com.peekcart.product.presentation.dto.response;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 목록 응답 DTO.
 */
public record ProductResponse(Long id, String name, long price, String imageUrl, String status) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name());
    }
}
