package com.peekcart.order.application.port;

/**
 * Order 도메인이 Product 도메인에 요청하는 오퍼레이션을 정의한다.
 * 구현체는 Product infrastructure 레이어에 위치한다.
 */
public interface ProductPort {

    /**
     * 상품 존재 여부를 검증한다.
     *
     * @param productId 상품 PK
     * @throws RuntimeException 상품이 존재하지 않으면 예외
     */
    void verifyProductExists(Long productId);

    /**
     * 재고를 차감하고 상품 단가를 반환한다.
     *
     * @param productId 상품 PK
     * @param quantity  차감 수량
     * @return 상품 단가 (주문 시점 스냅샷)
     * @throws RuntimeException 상품 미존재 또는 재고 부족 시 예외
     */
    long decreaseStockAndGetUnitPrice(Long productId, int quantity);

    /**
     * 재고를 복구한다.
     *
     * @param productId 상품 PK
     * @param quantity  복구 수량
     * @throws RuntimeException 상품 미존재 시 예외
     */
    void restoreStock(Long productId, int quantity);
}
