package com.peekcart.notification.infrastructure.event;

import com.peekcart.notification.application.NotificationCommandService;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.order.domain.event.OrderCancelledEvent;
import com.peekcart.order.domain.event.OrderCreatedEvent;
import com.peekcart.payment.domain.event.PaymentCompletedEvent;
import com.peekcart.payment.domain.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 이벤트를 수신하여 알림을 생성한다.
 */
@Slf4j
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationCommandService notificationCommandService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOrderCreated(OrderCreatedEvent event) {
        String message = String.format("주문이 생성되었습니다. [주문번호: %s, 금액: %,d원]",
                event.orderNumber(), event.totalAmount());
        notificationCommandService.createNotification(event.userId(), NotificationType.ORDER_CREATED, message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        String message = String.format("결제가 완료되었습니다. [주문 ID: %d, 금액: %,d원, 결제수단: %s]",
                event.orderId(), event.amount(), event.method());
        notificationCommandService.createNotification(event.userId(), NotificationType.PAYMENT_COMPLETED, message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentFailed(PaymentFailedEvent event) {
        String message = String.format("결제에 실패했습니다. [주문 ID: %d, 금액: %,d원]",
                event.orderId(), event.amount());
        notificationCommandService.createNotification(event.userId(), NotificationType.PAYMENT_FAILED, message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOrderCancelled(OrderCancelledEvent event) {
        String message = String.format("주문이 취소되었습니다. [주문번호: %s]", event.orderNumber());
        notificationCommandService.createNotification(event.userId(), NotificationType.ORDER_CANCELLED, message);
    }
}
