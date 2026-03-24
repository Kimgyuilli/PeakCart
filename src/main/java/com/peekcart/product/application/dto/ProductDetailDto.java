package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 상세 조회 결과를 담는 Application 레이어 DTO.
 */
public record ProductDetailDto(Product product, int stock) {
}
