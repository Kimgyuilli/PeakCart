package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.lock.DistributedLockManager;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 재고 차감/복구를 담당하는 애플리케이션 서비스.
 * Order 도메인에서 호출하여 재고를 관리한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private static final String LOCK_KEY_PREFIX = "inventory-lock:";
    private static final long LOCK_WAIT_TIME = 3;
    private static final long LOCK_LEASE_TIME = 5;

    private final InventoryRepository inventoryRepository;
    private final DistributedLockManager lockManager;

    /**
     * 재고를 차감한다. Redis 분산 락(1차) + 낙관적 락({@code @Version}, 2차) 이중 방어.
     *
     * @param productId 상품 PK
     * @param quantity  차감 수량
     * @throws ProductException 상품 재고가 없으면 {@code PRD-001}, 재고 부족이면 {@code PRD-002},
     *                          락 획득 실패 시 {@code PRD-004}
     */
    public void decreaseStock(Long productId, int quantity) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        boolean locked = lockManager.tryLock(lockKey, LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
        if (!locked) {
            throw new ProductException(ErrorCode.PRD_004);
        }
        try {
            Inventory inventory = inventoryRepository.findByProductId(productId)
                    .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
            inventory.decrease(quantity);
        } finally {
            lockManager.unlock(lockKey);
        }
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
