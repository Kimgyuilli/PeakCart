package com.peekcart.order.infrastructure.event;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.payment.domain.event.PaymentCompletedEvent;
import com.peekcart.payment.domain.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 도메인 이벤트를 수신하여 주문 상태를 전이한다.
 * Task 1-4에서 보류했던 OrderEventListener 구현.
 */
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderRepository orderRepository;
    private final ProductPort productPort;

    /**
     * 결제 승인 이벤트 수신 시 주문을 PAYMENT_COMPLETED로 전이한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
        order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
    }

    /**
     * 결제 실패 이벤트 수신 시 주문을 취소하고 재고를 복구한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentFailed(PaymentFailedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
        order.cancel();
        for (var item : order.getOrderItems()) {
            productPort.restoreStock(item.getProductId(), item.getQuantity());
        }
    }
}
