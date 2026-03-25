package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.application.port.OrderPort;
import com.peekcart.payment.domain.event.PaymentApprovedEvent;
import com.peekcart.payment.domain.event.PaymentFailedEvent;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.toss.TossConfirmResponse;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ServiceTest
@DisplayName("PaymentCommandService 단위 테스트")
class PaymentCommandServiceTest {

    @InjectMocks PaymentCommandService paymentCommandService;
    @Mock PaymentRepository paymentRepository;
    @Mock TossPaymentClient tossPaymentClient;
    @Mock OrderPort orderPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("confirmPayment: 결제 승인이 성공하면 APPROVED 상태로 전이되고 이벤트가 발행된다")
    void confirmPayment_success() {
        Payment payment = PaymentFixture.paymentWithId();
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));
        given(tossPaymentClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResponse(
                        command.paymentKey(), command.orderId().toString(),
                        "DONE", "CARD", "2026-03-25T12:00:00+09:00"));

        PaymentDetailDto result = paymentCommandService.confirmPayment(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.method()).isEqualTo("CARD");
        then(eventPublisher).should().publishEvent(any(PaymentApprovedEvent.class));
    }

    @Test
    @DisplayName("confirmPayment: 결제 정보가 없으면 PAY_003 예외가 발생한다")
    void confirmPayment_notFound_throwsPAY003() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(command))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_003));
    }

    @Test
    @DisplayName("confirmPayment: 금액이 불일치하면 PAY_001 예외가 발생한다")
    void confirmPayment_amountMismatch_throwsPAY001() {
        Payment payment = PaymentFixture.paymentWithId();
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, 50_000L);

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(command))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_001));
    }

    @Test
    @DisplayName("confirmPayment: Toss API 호출 실패 시 FAILED 상태로 전이되고 PAY_005 예외가 발생한다")
    void confirmPayment_tossFailure_throwsPAY005() {
        Payment payment = PaymentFixture.paymentWithId();
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));
        willThrow(new RuntimeException("Toss API Error"))
                .given(tossPaymentClient).confirm(anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(command))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_005));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        then(eventPublisher).should().publishEvent(any(PaymentFailedEvent.class));
    }
}
