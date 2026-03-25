package com.peekcart.payment.application.port;

/**
 * Payment 도메인이 Order 도메인에 요청하는 오퍼레이션을 정의한다.
 * 구현체는 Order infrastructure 레이어에 위치한다.
 */
public interface OrderPort {

    /**
     * 주문 상태를 PAYMENT_REQUESTED로 전이한다.
     *
     * @throws RuntimeException 주문이 존재하지 않거나 전이 불가 상태면 예외
     */
    void transitionToPaymentRequested(Long orderId);

    /**
     * 주문이 해당 사용자 소유인지 검증한다.
     *
     * @throws RuntimeException 주문이 존재하지 않거나 본인 주문이 아니면 예외
     */
    void verifyOrderOwner(Long userId, Long orderId);
}
