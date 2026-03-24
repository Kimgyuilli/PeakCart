package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감/복구를 담당하는 애플리케이션 서비스.
 * Order 도메인에서 호출하여 재고를 관리한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * 재고를 차감한다. 낙관적 락({@code @Version})이 동시성을 제어한다.
     *
     * @param productId 상품 PK
     * @param quantity  차감 수량
     * @throws ProductException 상품 재고가 없으면 {@code PRD-001}, 재고 부족이면 {@code PRD-002}
     */
    public void decreaseStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.decrease(quantity);
    }

    /**
     * 재고를 복구한다.
     *
     * @param productId 상품 PK
     * @param quantity  복구 수량
     * @throws ProductException 상품 재고가 없으면 {@code PRD-001}
     */
    public void restoreStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.restore(quantity);
    }
}
