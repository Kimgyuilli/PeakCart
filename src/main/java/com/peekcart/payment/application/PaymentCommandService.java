package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.application.port.OrderPort;
import com.peekcart.payment.domain.event.PaymentCompletedEvent;
import com.peekcart.payment.domain.event.PaymentFailedEvent;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.toss.TossConfirmResponse;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 결제 승인을 처리하는 애플리케이션 서비스.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final OrderPort orderPort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 결제를 승인한다.
     *
     * @throws PaymentException 결제 정보 미존재 시 {@code PAY-003}, 금액 불일치 시 {@code PAY-001}
     */
    public PaymentDetailDto confirmPayment(Long userId, ConfirmPaymentCommand command) {
        orderPort.verifyOrderOwner(userId, command.orderId());

        Payment payment = paymentRepository.findByOrderId(command.orderId())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));

        payment.validateAmount(command.amount());
        orderPort.transitionToPaymentRequested(command.orderId());
        payment.assignPaymentKey(command.paymentKey());

        try {
            TossConfirmResponse response = tossPaymentClient.confirm(
                    command.paymentKey(), command.orderId().toString(), command.amount());
            payment.approve(response.method(), OffsetDateTime.parse(response.approvedAt()).toLocalDateTime());

            eventPublisher.publishEvent(new PaymentCompletedEvent(
                    payment.getId(), payment.getOrderId(), userId, payment.getPaymentKey(),
                    payment.getAmount(), payment.getMethod()));
        } catch (Exception e) {
            log.error("Toss 결제 승인 실패 — orderId={}, paymentKey={}: {}",
                    command.orderId(), command.paymentKey(), e.getMessage());
            payment.fail();
            eventPublisher.publishEvent(new PaymentFailedEvent(
                    payment.getId(), payment.getOrderId(), userId, payment.getPaymentKey(), payment.getAmount()));
        }

        return PaymentDetailDto.from(payment);
    }
}
