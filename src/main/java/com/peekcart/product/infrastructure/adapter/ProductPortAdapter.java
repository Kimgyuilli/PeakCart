package com.peekcart.product.infrastructure.adapter;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ProductPort}의 구현체. Product 도메인 내부를 캡슐화하여
 * Order 도메인이 Product 세부사항에 직접 의존하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductPortAdapter implements ProductPort {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    public void verifyProductExists(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
    }

    @Override
    public long decreaseStockAndGetUnitPrice(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.decrease(quantity);

        return product.getPrice();
    }

    @Override
    public void restoreStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.restore(quantity);
    }
}
