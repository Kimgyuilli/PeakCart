package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.CategoryRepository;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.product.presentation.dto.request.CreateProductRequest;
import com.peekcart.product.presentation.dto.request.UpdateProductRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록/수정/삭제를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ProductCommandService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 새 상품을 등록하고 초기 재고를 생성한다.
     *
     * @param request 상품 등록 요청
     * @return 생성된 상품 상세 정보
     * @throws ProductException 카테고리가 없으면 {@code PRD-003}
     */
    public ProductDetailDto create(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_003));

        Product product = Product.create(
                category, request.name(), request.description(), request.price(), request.imageUrl());
        productRepository.save(product);

        Inventory inventory = Inventory.create(product, request.stock());
        inventoryRepository.save(inventory);

        return new ProductDetailDto(product, inventory.getStock());
    }

    /**
     * 상품 정보를 수정한다.
     *
     * @param productId 수정할 상품 PK
     * @param request   수정 요청
     * @return 수정된 상품 상세 정보
     * @throws ProductException 상품이 없으면 {@code PRD-001}, 카테고리가 없으면 {@code PRD-003}
     */
    public ProductDetailDto update(Long productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_003));

        product.update(category, request.name(), request.description(), request.price(), request.imageUrl());

        int stock = inventoryRepository.findByProductId(productId)
                .map(Inventory::getStock)
                .orElse(0);

        return new ProductDetailDto(product, stock);
    }

    /**
     * 상품을 판매 중단 처리한다 (soft delete).
     *
     * @param productId 삭제할 상품 PK
     * @throws ProductException 상품이 없으면 {@code PRD-001}
     */
    public void delete(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        product.discontinue();
    }
}
