package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 판매 중인 상품 목록을 페이징으로 조회한다.
     *
     * @param categoryId 카테고리 필터 (null이면 전체)
     * @param pageable   페이징 정보
     * @return 상품 페이지
     */
    public Page<Product> getProducts(Long categoryId, Pageable pageable) {
        if (categoryId != null) {
            return productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ON_SALE, pageable);
        }
        return productRepository.findByStatus(ProductStatus.ON_SALE, pageable);
    }

    /**
     * 상품 상세 정보를 조회한다.
     *
     * @param productId 조회할 상품 PK
     * @return 상품 상세 DTO (상품 + 재고)
     * @throws ProductException 상품이 없으면 {@code PRD-001}
     */
    public ProductDetailDto getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));

        int stock = inventoryRepository.findByProductId(productId)
                .map(inventory -> inventory.getStock())
                .orElse(0);

        return new ProductDetailDto(product, stock);
    }
}
