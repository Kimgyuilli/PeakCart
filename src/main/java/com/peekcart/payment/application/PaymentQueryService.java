package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.application.port.OrderPort;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final OrderPort orderPort;

    /**
     * @throws PaymentException 결제 정보 미존재 시 {@code PAY-003}
     * @throws OrderException 본인 주문이 아니면 {@code ORD-001}
     */
    public PaymentDetailDto getPaymentByOrderId(Long userId, Long orderId) {
        orderPort.verifyOrderOwner(userId, orderId);
        return paymentRepository.findByOrderId(orderId)
                .map(PaymentDetailDto::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));
    }
}
