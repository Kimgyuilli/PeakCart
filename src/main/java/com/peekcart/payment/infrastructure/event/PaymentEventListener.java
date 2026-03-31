package com.peekcart.payment.infrastructure.event;

import com.peekcart.order.domain.event.OrderCreatedEvent;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 도메인 이벤트를 수신하여 결제 레코드를 생성한다.
 */
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentRepository paymentRepository;

    /**
     * 주문 생성 이벤트 수신 시 PENDING 상태의 결제 레코드를 생성한다.
     * AFTER_COMMIT 이후 새 트랜잭션에서 실행된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOrderCreated(OrderCreatedEvent event) {
        Payment payment = Payment.create(event.orderId(), event.totalAmount());
        paymentRepository.save(payment);
    }
}
